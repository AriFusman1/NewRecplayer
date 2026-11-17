package com.pcapplayer.net;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Reassembles IPv4 fragments keyed by (src, dst, protocol, id). A reassembled packet carries the
 * timestamp of the fragment that completed it. Sets that never complete (or time out) are counted
 * as incomplete and dropped.
 */
public final class Ipv4Reassembler {

    private record Key(int src, int dst, int proto, int id) {
    }

    private static final class FragSet {
        final long firstTs;
        final List<int[]> ranges = new ArrayList<>(); // [start, end) already covered
        final List<byte[]> parts = new ArrayList<>();
        final List<Integer> offsets = new ArrayList<>();
        int totalLen = -1;
        boolean truncated;

        FragSet(long firstTs) {
            this.firstTs = firstTs;
        }

        boolean complete() {
            if (totalLen < 0) return false;
            List<int[]> sorted = new ArrayList<>(ranges);
            sorted.sort((a, b) -> Integer.compare(a[0], b[0]));
            int covered = 0;
            for (int[] r : sorted) {
                if (r[0] > covered) return false;
                covered = Math.max(covered, r[1]);
            }
            return covered >= totalLen;
        }
    }

    private final long timeoutNanos;
    private final Map<Key, FragSet> pending = new HashMap<>();
    private long incomplete;
    private long lastPurgeTs;

    public Ipv4Reassembler(long timeoutNanos) {
        this.timeoutNanos = timeoutNanos;
    }

    /**
     * @return the packet itself if not a fragment, the reassembled packet if this fragment
     * completed a set, or null while the set is still incomplete
     */
    public Ipv4Packet accept(Ipv4Packet p) {
        purge(p.tsNanos());
        if (!p.isFragment()) return p;

        Key key = new Key(p.src(), p.dst(), p.protocol(), p.id());
        FragSet set = pending.computeIfAbsent(key, k -> new FragSet(p.tsNanos()));
        int start = p.fragmentOffset();
        int end = start + p.payload().length;
        set.ranges.add(new int[]{start, end});
        set.parts.add(p.payload());
        set.offsets.add(start);
        set.truncated |= p.truncated();
        if (!p.moreFragments()) {
            set.totalLen = end;
        }
        if (!set.complete()) return null;

        pending.remove(key);
        byte[] whole = new byte[set.totalLen];
        // Later fragments overwrite earlier ones on overlap; with no overlap order does not matter.
        for (int i = 0; i < set.parts.size(); i++) {
            byte[] part = set.parts.get(i);
            int off = set.offsets.get(i);
            int len = Math.min(part.length, whole.length - off);
            if (len > 0) System.arraycopy(part, 0, whole, off, len);
        }
        return new Ipv4Packet(p.tsNanos(), p.src(), p.dst(), p.protocol(), p.id(), false, 0, whole, set.truncated, null);
    }

    private void purge(long now) {
        if (now - lastPurgeTs < timeoutNanos / 4) return;
        lastPurgeTs = now;
        Iterator<FragSet> it = pending.values().iterator();
        while (it.hasNext()) {
            if (now - it.next().firstTs > timeoutNanos) {
                it.remove();
                incomplete++;
            }
        }
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
