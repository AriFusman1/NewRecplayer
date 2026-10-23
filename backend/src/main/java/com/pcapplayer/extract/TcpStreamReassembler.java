package com.pcapplayer.extract;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
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
final class TcpStreamReassembler {

    record Chunk(long tsNanos, byte[] data) {
    }

    record Gap(long tsNanos, long bytes) {
    }

    private record Held(long tsNanos, byte[] data) {
    }

    private final long gapTimeoutNanos;
    private final List<Chunk> chunks = new ArrayList<>();
    private final List<Gap> gaps = new ArrayList<>();
    private final TreeMap<Long, Held> held = new TreeMap<>();
    private boolean initialized;
    private long next;          // next expected absolute sequence number
    private long ackedUpTo = -1; // highest absolute ACK seen from the receiver
    private long lastChunkTs = Long.MIN_VALUE;
    private long bytes;

    TcpStreamReassembler(long gapTimeoutNanos) {
        this.gapTimeoutNanos = gapTimeoutNanos;
    }

    /** SYN seen: the stream starts at ISN + 1. */
    void syn(long ts, long seq32) {
        if (!initialized) {
            initialized = true;
            next = seq32 + 1;
        }
    }

    void segment(long ts, long seq32, byte[] payload) {
        expireHeld(ts);
        if (payload.length == 0) return;
        if (!initialized) {
            initialized = true; // mid-connection start: the stream begins at the first segment we see
            next = seq32;
        }
        long start = unwrap(seq32);
        long end = start + payload.length;
        if (end <= next) return; // pure retransmission
        if (start > next) {
            if (ackedUpTo >= start) {
                // the receiver already acknowledged up to here: the hole was lost by the capture
                skipGap(ts, start);
            } else {
                hold(ts, start, payload);
                return;
            }
        }
        int skip = (int) (next - start);
        ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length - skip);
        out.write(payload, skip, payload.length - skip);
        next = end;
        drainHeld(out);
        emit(ts, out.toByteArray());
    }

    /** ACK observed from the receiving side (the service). */
    void ack(long ts, long ack32) {
        if (!initialized) return;
        long a = unwrap(ack32);
        if (a > ackedUpTo) ackedUpTo = a;
        // the receiver acknowledged bytes up to (at least) the first held segment: the hole was a capture drop
        while (!held.isEmpty() && ackedUpTo >= held.firstKey()) {
            flushThroughFirstHole();
        }
        expireHeld(ts);
    }

    /** End of stream (FIN/RST or end of capture): anything held is behind a gap. */
    void finish() {
        while (!held.isEmpty()) {
            flushThroughFirstHole();
        }
    }

    List<Chunk> chunks() {
        return chunks;
    }

    List<Gap> gaps() {
        return gaps;
    }

    long bytes() {
        return bytes;
    }

    private void expireHeld(long now) {
        while (!held.isEmpty() && now - oldestHeldTs() > gapTimeoutNanos) {
            flushThroughFirstHole();
        }
    }

    private long oldestHeldTs() {
        long min = Long.MAX_VALUE;
        for (Held h : held.values()) min = Math.min(min, h.tsNanos());
        return min;
    }

    /**
     * Declares the hole in front of the first held segment a gap, then releases every held segment
     * that is now contiguous. Each keeps its own arrival time: the host did receive the missing
     * bytes, so the application read each segment when it arrived (or when an earlier one did).
     */
    private void flushThroughFirstHole() {
        Map.Entry<Long, Held> first = held.firstEntry();
        if (first.getKey() > next) {
            skipGap(first.getValue().tsNanos(), first.getKey());
        }
        while (!held.isEmpty() && held.firstKey() <= next) {
            Map.Entry<Long, Held> e = held.pollFirstEntry();
            byte[] d = e.getValue().data();
            long end = e.getKey() + d.length;
            if (end > next) {
                int skip = (int) (next - e.getKey());
                byte[] part = skip == 0 ? d : java.util.Arrays.copyOfRange(d, skip, d.length);
                next = end;
                emit(e.getValue().tsNanos(), part);
            }
        }
    }

    private void skipGap(long ts, long to) {
        gaps.add(new Gap(ts, to - next));
        next = to;
    }

    private void hold(long ts, long start, byte[] payload) {
        Held existing = held.get(start);
        if (existing == null || existing.data().length < payload.length) {
            held.put(start, new Held(ts, payload));
        }
    }

    /** Appends every held segment that is now contiguous with {@code next}. */
    private void drainHeld(ByteArrayOutputStream out) {
        while (!held.isEmpty()) {
            Map.Entry<Long, Held> e = held.firstEntry();
            long s = e.getKey();
            if (s > next) break;
            held.pollFirstEntry();
            byte[] d = e.getValue().data();
            long end = s + d.length;
            if (end > next) {
                int skip = (int) (next - s);
                out.write(d, skip, d.length - skip);
                next = end;
            }
        }
    }

    private void emit(long ts, byte[] data) {
        if (data.length == 0) return;
        long t = Math.max(ts, lastChunkTs);
        lastChunkTs = t;
        bytes += data.length;
        chunks.add(new Chunk(t, data));
    }

    /** Maps a 32-bit sequence number to the 64-bit value closest to {@code next}. */
    private long unwrap(long seq32) {
        int diff = (int) (seq32 - (next & 0xFFFFFFFFL));
        return next + diff;
    }
}
