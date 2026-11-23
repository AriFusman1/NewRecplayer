package com.pcapplayer.pcapProcessing.flow;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Rebuilds one direction of a TCP connection exactly as the receiving application read it.
 * <ul>
 *   <li>retransmitted / duplicate bytes are dropped, overlapping segments trimmed;</li>
 *   <li>out-of-order segments are held until the hole is filled; the bytes that become readable
 *       form one chunk stamped with the time of the segment that made them readable;</li>
 *   <li>a hole is declared a capture <em>gap</em> when the receiver's own ACK already covers it
 *       (the host got the bytes, the capture did not), when it has been open longer than the gap
 *       timeout, or when the stream ends. Gap bytes are skipped; held data keeps its own arrival time.</li>
 * </ul>
 * Sequence numbers are unwrapped to 64 bit, so wrap-around is handled.
 */
public class TcpStreamReassembler {

    public record Chunk(long tsNanos, byte[] data) {
    }

    public record Gap(long tsNanos, long bytes) {
    }

    private record Held(long tsNanos, byte[] data) {
    }

    private final long gapTimeoutNanos;
    private final List<Chunk> chunks = new ArrayList<>();
    private final List<Gap> gaps = new ArrayList<>();
    private final TreeMap<Long, Held> held = new TreeMap<>();
    private boolean initialized;
    private long nextExpectedSeq;          // next expected absolute sequence number
    private long ackedUpTo = -1; // highest absolute ACK seen from the receiver
    private long lastChunkTs = Long.MIN_VALUE;
    private long bytes;

    public TcpStreamReassembler(long gapTimeoutNanos) {
        this.gapTimeoutNanos = gapTimeoutNanos;
    }

    /** SYN seen: the stream starts at ISN + 1. */
    public void syn(long tsNanos, long seq32) {
        if (!initialized) {
            initialized = true;
            nextExpectedSeq = seq32 + 1;
        }
    }

    public void segment(long tsNanos, long seq32, byte[] payload) {
        expireHeld(tsNanos);
        if (payload.length == 0) return;

        if (!initialized) {
            initialized = true; // mid-connection start: the stream begins at the first segment we see
            nextExpectedSeq = seq32;
        }
        long start = unwrap(seq32);
        long end = start + payload.length;
        if (end <= nextExpectedSeq) return; // pure retransmission
        if (start > nextExpectedSeq) {
            if (ackedUpTo >= start) {
                // the receiver already acknowledged up to here: the hole was lost by the capture
                skipGap(tsNanos, start);
            } else {
                hold(tsNanos, start, payload);
                return;
            }
        }
        int skip = (int) (nextExpectedSeq - start);
        ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length - skip);
        out.write(payload, skip, payload.length - skip);
        nextExpectedSeq = end;
        drainHeld(out);
        emit(tsNanos, out.toByteArray());
    }

    /** ACK observed from the receiving side (the service). */
    public void ack(long tsNanos, long ack32) {
        if (!initialized) return;
        long ackedSeq = unwrap(ack32);
        if (ackedSeq > ackedUpTo) ackedUpTo = ackedSeq;
        // the receiver acknowledged bytes up to (at least) the first held segment: the hole was a capture drop
        while (!held.isEmpty() && ackedUpTo >= held.firstKey()) {
            flushThroughFirstHole();
        }
        expireHeld(tsNanos);
    }

    /** End of stream (FIN/RST or end of capture): anything held is behind a gap. */
    public void finish() {
        while (!held.isEmpty()) {
            flushThroughFirstHole();
        }
    }

    public List<Chunk> chunks() {
        return chunks;
    }

    public List<Gap> gaps() {
        return gaps;
    }

    public long bytes() {
        return bytes;
    }

    private void expireHeld(long now) {
        while (!held.isEmpty() && now - oldestHeldTs() > gapTimeoutNanos) {
            flushThroughFirstHole();
        }
    }

    private long oldestHeldTs() {
        long min = Long.MAX_VALUE;
        for (Held heldSegment : held.values()) min = Math.min(min, heldSegment.tsNanos());
        return min;
    }

    /**
     * Declares the hole in front of the first held segment a gap, then releases every held segment
     * that is now contiguous. Each keeps its own arrival time: the host did receive the missing
     * bytes, so the application read each segment when it arrived (or when an earlier one did).
     */
    private void flushThroughFirstHole() {
        Map.Entry<Long, Held> first = held.firstEntry();

        if (first.getKey() > nextExpectedSeq) {
            skipGap(first.getValue().tsNanos(), first.getKey());
        }

        while (!held.isEmpty() && held.firstKey() <= nextExpectedSeq) {
            Map.Entry<Long, Held> entry = held.pollFirstEntry();
            byte[] data = entry.getValue().data();
            long end = entry.getKey() + data.length;

            if (end > nextExpectedSeq) {
                int skip = (int) (nextExpectedSeq - entry.getKey());
                byte[] part = skip == 0 ? data : Arrays.copyOfRange(data, skip, data.length);
                nextExpectedSeq = end;
                emit(entry.getValue().tsNanos(), part);
            }
        }
    }

    private void skipGap(long tsNanos, long to) {
        gaps.add(new Gap(tsNanos, to - nextExpectedSeq));
        nextExpectedSeq = to;
    }

    private void hold(long tsNanos, long start, byte[] payload) {
        Held existing = held.get(start);

        if (existing == null || existing.data().length < payload.length) {
            held.put(start, new Held(tsNanos, payload));
        }
    }

    /** Appends every held segment that is now contiguous with {@code next}. */
    private void drainHeld(ByteArrayOutputStream out) {
        while (!held.isEmpty()) {
            Map.Entry<Long, Held> entry = held.firstEntry();
            long start = entry.getKey();
            if (start > nextExpectedSeq) break;
            held.pollFirstEntry();
            byte[] data = entry.getValue().data();
            long end = start + data.length;

            if (end > nextExpectedSeq) {
                int skip = (int) (nextExpectedSeq - start);
                out.write(data, skip, data.length - skip);
                nextExpectedSeq = end;
            }
        }
    }

    private void emit(long tsNanos, byte[] data) {
        if (data.length == 0) return;
        long chunkTs = Math.max(tsNanos, lastChunkTs);
        lastChunkTs = chunkTs;
        bytes += data.length;
        chunks.add(new Chunk(chunkTs, data));
    }

    /** Maps a 32-bit sequence number to the 64-bit value closest to {@code next}. */
    private long unwrap(long seq32) {
        int diff = (int) (seq32 - (nextExpectedSeq & 0xFFFFFFFFL));
        return nextExpectedSeq + diff;
    }
}
