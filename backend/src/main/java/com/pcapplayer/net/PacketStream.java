package com.pcapplayer.net;

import com.pcapplayer.capture.CaptureReader;
import com.pcapplayer.capture.RawFrame;

import java.io.Closeable;
import java.io.IOException;

/**
 * Frames → IPv4 → fragment reassembly → TCP/UDP packets. Everything that is not IPv4 TCP/UDP is
 * skipped silently. Counters describe the whole stream (all packets, before any filtering).
 */
public final class PacketStream implements Closeable {

    private final CaptureReader reader;
    private final Ipv4Reassembler reassembler;
    private long frames;
    private long firstTs = Long.MAX_VALUE;
    private long lastTs = Long.MIN_VALUE;
    private boolean finished;

    public PacketStream(CaptureReader reader, long fragmentTimeoutNanos) {
        this.reader = reader;
        this.reassembler = new Ipv4Reassembler(fragmentTimeoutNanos);
    }

    /** @return the next TCP/UDP packet, or null at end of capture */
    public L4Packet next() throws IOException {
        RawFrame f;
        while ((f = reader.next()) != null) {
            frames++;
            if (f.tsNanos() > 0) {
                firstTs = Math.min(firstTs, f.tsNanos());
                lastTs = Math.max(lastTs, f.tsNanos());
            }
            int off = LinkDecoder.ipv4Offset(f.linkType(), f.data());
            if (off < 0) continue;
            Ipv4Packet ip = Ipv4Packet.parse(f.tsNanos(), f.data(), off, f.truncated());
            if (ip == null) continue;
            if (ip.protocol() != Ipv4Packet.PROTO_TCP && ip.protocol() != Ipv4Packet.PROTO_UDP) continue;
            Ipv4Packet whole = reassembler.accept(ip);
            if (whole == null) continue;
            L4Packet p = L4Packet.decode(whole);
            if (p != null) return p;
        }
        if (!finished) {
            finished = true;
            reassembler.finish();
        }
        return null;
    }

    public long frames() {
        return frames;
    }

    public long firstTs() {
        return firstTs == Long.MAX_VALUE ? 0 : firstTs;
    }

    public long lastTs() {
        return lastTs == Long.MIN_VALUE ? 0 : lastTs;
    }

    public long incompleteFragmentSets() {
        return reassembler.incompleteCount();
    }

    public boolean endedAbruptly() {
        return reader.endedAbruptly();
    }

    public String format() {
        return reader.format();
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}
