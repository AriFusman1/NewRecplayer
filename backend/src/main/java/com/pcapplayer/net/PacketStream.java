package com.pcapplayer.net;

import com.pcapplayer.capture.CaptureReader;
import com.pcapplayer.capture.RawFrame;
import org.pcap4j.packet.IpV4Packet;

import java.io.Closeable;
import java.io.IOException;

/**
 * Frames (libpcap) → IPv4 (pcap4j) → fragment reassembly → TCP/UDP packets (pcap4j). Everything that is not IPv4 TCP/UDP is
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
        RawFrame frame;
        while ((frame = reader.next()) != null) {
            frames++;
            if (frame.tsNanos() > 0) {
                firstTs = Math.min(firstTs, frame.tsNanos());
                lastTs = Math.max(lastTs, frame.tsNanos());
            }
            IpV4Packet decodedIp = LinkDecoder.ipv4(frame);
            if (decodedIp == null) continue;
            Ipv4Packet ipPacket = Ipv4Packet.from(frame.tsNanos(), decodedIp, frame.truncated());
            if (ipPacket.protocol() != Ipv4Packet.PROTO_TCP && ipPacket.protocol() != Ipv4Packet.PROTO_UDP) continue;
            Ipv4Packet completePacket = reassembler.accept(ipPacket);
            if (completePacket == null) continue; // fragment of a set that is not complete yet
            L4Packet transportPacket = L4Packet.decode(completePacket);
            if (transportPacket != null) return transportPacket;
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
