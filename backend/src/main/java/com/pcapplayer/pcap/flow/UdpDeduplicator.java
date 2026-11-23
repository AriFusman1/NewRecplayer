package com.pcapplayer.pcap.flow;

import com.pcapplayer.pcap.decode.L4Packet;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Detects the same UDP datagram captured twice (e.g. "-i any", bonded or VLAN interfaces):
 * same 5-tuple, IP ID and payload within a short window.
 */
public class UdpDeduplicator {

    private record Key(int srcIp, int dstIp, int srcPort, int dstPort, int ipId, int hash, int len) {
    }

    private record Seen(Key key, long tsNanos, byte[] payload) {
    }

    private final long windowNanos;
    private final Map<Key, Seen> recent = new HashMap<>();
    private final ArrayDeque<Seen> order = new ArrayDeque<>();
    private long duplicates;

    public UdpDeduplicator(long windowNanos) {
        this.windowNanos = windowNanos;
    }

    /** @return true if this datagram duplicates one seen within the window */
    public boolean isDuplicate(L4Packet packet) {
        long tsNanos = packet.tsNanos();
        while (!order.isEmpty() && tsNanos - order.peekFirst().tsNanos() > windowNanos) {
            Seen old = order.pollFirst();
            recent.remove(old.key(), old);
        }
        Key key = new Key(packet.srcIp(), packet.dstIp(), packet.srcPort(), packet.dstPort(), packet.ipId(),
                Arrays.hashCode(packet.payload()), packet.payload().length);
        Seen previousCopy = recent.get(key);
        if (previousCopy != null && Arrays.equals(previousCopy.payload(), packet.payload())) {
            duplicates++;
            return true;
        }
        Seen seen = new Seen(key, tsNanos, packet.payload());
        recent.put(key, seen);
        order.addLast(seen);
        return false;
    }

    public long duplicates() {
        return duplicates;
    }
}
