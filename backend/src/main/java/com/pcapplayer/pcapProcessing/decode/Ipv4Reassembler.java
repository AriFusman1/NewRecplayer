package com.pcapplayer.pcapProcessing.decode;

import java.util.HashMap;
import java.util.Map;

/**
 * Reassembles IPv4 fragments keyed by (src, dst, protocol, id). A reassembled packet carries the
 * timestamp of the fragment that completed it. Sets that never complete (or time out) are counted
 * as incomplete and dropped.
 */
public class Ipv4Reassembler {

    private record Key(int src, int dst, int protocol, int id) {
    }

    private final long timeoutNanos;
    private final Map<Key, Ipv4Fragments> pending = new HashMap<>();
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
        Ipv4Fragments fragments = pending.computeIfAbsent(key, newKey -> new Ipv4Fragments(packet.tsNanos()));
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
