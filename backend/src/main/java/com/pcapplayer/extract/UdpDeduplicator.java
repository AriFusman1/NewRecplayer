package com.pcapplayer.extract;

import com.pcapplayer.net.L4Packet;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Detects the same UDP datagram captured twice (e.g. "-i any", bonded or VLAN interfaces):
 * same 5-tuple, IP ID and payload within a short window.
 */
final class UdpDeduplicator {

    private record Key(int srcIp, int dstIp, int srcPort, int dstPort, int ipId, int hash, int len) {
    }

    private record Seen(Key key, long ts, byte[] payload) {
    }

    private final long windowNanos;
    private final Map<Key, Seen> recent = new HashMap<>();
    private final ArrayDeque<Seen> order = new ArrayDeque<>();
    private long duplicates;

    UdpDeduplicator(long windowNanos) {
        this.windowNanos = windowNanos;
    }

    /** @return true if this datagram duplicates one seen within the window */
    boolean isDuplicate(L4Packet p) {
        long ts = p.tsNanos();
        while (!order.isEmpty() && ts - order.peekFirst().ts() > windowNanos) {
            Seen old = order.pollFirst();
            recent.remove(old.key(), old);
        }
        Key key = new Key(p.srcIp(), p.dstIp(), p.srcPort(), p.dstPort(), p.ipId(),
                Arrays.hashCode(p.payload()), p.payload().length);
        Seen prev = recent.get(key);
        if (prev != null && Arrays.equals(prev.payload(), p.payload())) {
            duplicates++;
            return true;
        }
        Seen s = new Seen(key, ts, p.payload());
        recent.put(key, s);
        order.addLast(s);
        return false;
    }

    long duplicates() {
        return duplicates;
    }
}
