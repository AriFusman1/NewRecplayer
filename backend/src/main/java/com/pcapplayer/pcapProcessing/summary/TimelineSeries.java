package com.pcapplayer.pcapProcessing.summary;

import java.util.Arrays;

/**
 * One endpoint's packet counts on a {@link Timeline}'s buckets, stored sparsely: only buckets
 * {@code from .. from+length-1} are kept.
 */
final class TimelineSeries {

    private int from = -1;
    private int length;
    private int[] counts = new int[4];

    void add(int index) {
        if (from < 0) {
            from = index;
            length = 1;
            counts[0] = 1;
            return;
        }
        if (index < from) { // out-of-order timestamp before what we have: grow to the left
            int shift = from - index;
            int[] grown = new int[Math.max(counts.length, length + shift)];
            System.arraycopy(counts, 0, grown, shift, length);
            counts = grown;
            from = index;
            length += shift;
        } else if (index >= from + length) {
            int needed = index - from + 1;
            if (needed > counts.length) counts = Arrays.copyOf(counts, Math.max(needed, counts.length * 2));
            length = needed;
        }
        counts[index - from]++;
    }

    /** Follows the timeline when its buckets double in width (pairs of buckets merge). */
    void halve() {
        if (from < 0) return;
        int halvedFrom = from / 2;
        int halvedLength = (from + length - 1) / 2 - halvedFrom + 1;
        int[] halved = new int[Math.max(4, halvedLength)];
        for (int bucket = 0; bucket < length; bucket++) halved[(from + bucket) / 2 - halvedFrom] += counts[bucket];
        counts = halved;
        from = halvedFrom;
        length = halvedLength;
    }

    int from() {
        return Math.max(0, from);
    }

    int[] counts() {
        return Arrays.copyOf(counts, length);
    }
}
