package com.pcapplayer.pcap.decode;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reassembles IPv4 fragments keyed by (src, dst, protocol, id). A reassembled packet carries the
 * timestamp of the fragment that completed it. Sets that never complete (or time out) are counted
 * as incomplete and dropped.
 */
public class Ipv4Reassembler {

    private record Key(int src, int dst, int protocol, int id) {
    }

    /** One datagram's fragments by offset; a fragment captured twice simply replaces itself. */
    private static class Fragments {
        final long firstTsNanos;
        final TreeMap<Integer, byte[]> byOffset = new TreeMap<>();
        int totalLength = -1; // known once the last fragment (no "more fragments" flag) arrives
        boolean truncated;

        Fragments(long firstTsNanos) {
            this.firstTsNanos = firstTsNanos;
        }

        boolean complete() {
            if (totalLength < 0) return false;
            int covered = 0;
            for (Map.Entry<Integer, byte[]> fragment : byOffset.entrySet()) {
                if (fragment.getKey() > covered) return false; // a hole
                covered = Math.max(covered, fragment.getKey() + fragment.getValue().length);
            }
            return covered >= totalLength;
        }

        byte[] join() {
            byte[] whole = new byte[totalLength];
            byOffset.forEach((offset, data) -> {
                int length = Math.min(data.length, totalLength - offset);
                if (length > 0) System.arraycopy(data, 0, whole, offset, length);
            });
            return whole;
        }
    }

    private final long timeoutNanos;
    private final Map<Key, Fragments> pending = new HashMap<>();
    private long incomplete;
    private long lastExpiryCheckTs;

    public Ipv4Reassembler(long timeoutNanos) {
        this.timeoutNanos = timeoutNanos;
    }

    /**
     * @return the packet itself if not a fragment, the reassembled packet if this fragment
     * completed a set, or null while the set is still incomplete
     */
    public Ipv4Packet accept(Ipv4Packet packet) {
        dropExpired(packet.tsNanos());
        if (!packet.isFragment()) return packet;

        Key key = new Key(packet.src(), packet.dst(), packet.protocol(), packet.id());
        Fragments fragments = pending.computeIfAbsent(key, newKey -> new Fragments(packet.tsNanos()));
        fragments.byOffset.put(packet.fragmentOffset(), packet.payload());
        fragments.truncated |= packet.truncated();
        if (!packet.moreFragments()) fragments.totalLength = packet.fragmentOffset() + packet.payload().length;
        if (!fragments.complete()) return null;

        pending.remove(key);
        return new Ipv4Packet(packet.tsNanos(), packet.src(), packet.dst(), packet.protocol(), packet.id(),
                false, 0, fragments.join(), fragments.truncated, null);
    }

    /** Drops sets older than the timeout (checked a few times per timeout, not on every packet). */
    private void dropExpired(long nowNanos) {
        if (nowNanos - lastExpiryCheckTs < timeoutNanos / 4) return;
        lastExpiryCheckTs = nowNanos;
        int before = pending.size();
        pending.values().removeIf(fragments -> nowNanos - fragments.firstTsNanos > timeoutNanos);
        incomplete += before - pending.size();
    }

    /** Call at end of input: everything still pending is incomplete. */
    public void finish() {
        incomplete += pending.size();
        pending.clear();
    }

    public long incompleteCount() {
        return incomplete;
    }
}
