package com.pcapplayer.pcapProcessing.decode;

import java.util.Map;
import java.util.TreeMap;

/** One datagram's fragments by offset; a fragment captured twice simply replaces itself. */
final class Ipv4Fragments {

    final long firstTsNanos;
    final TreeMap<Integer, byte[]> byOffset = new TreeMap<>();
    int totalLength = -1; // known once the last fragment (no "more fragments" flag) arrives
    boolean truncated;

    Ipv4Fragments(long firstTsNanos) {
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
