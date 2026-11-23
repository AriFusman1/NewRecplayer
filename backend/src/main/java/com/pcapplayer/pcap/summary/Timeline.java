package com.pcapplayer.pcap.summary;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Packet counts over time, built in one streaming pass without knowing the capture length up
 * front: buckets start 1 ms wide and double (merging pairs) whenever the capture outgrows
 * {@link #MAX_BINS}. Per-endpoint series share the same buckets but are stored sparsely, so short
 * connections on ephemeral ports cost only a few ints.
 */
public class Timeline {

    static final int MAX_BINS = 256;

    private final long baseNanos;
    private long widthNanos = TimeUnit.MILLISECONDS.toNanos(1);
    private long[] total = new long[MAX_BINS];
    private int used;
    private final List<Series> series = new ArrayList<>();

    Timeline(long baseNanos) {
        this.baseNanos = baseNanos;
    }

    /** Bucket of a timestamp, widening the buckets first if it falls past the last one. */
    int index(long tsNanos) {
        while (true) {
            long i = Math.max(0, (tsNanos - baseNanos) / widthNanos);
            if (i < MAX_BINS) return (int) i;
            widen();
        }
    }

    void count(int index) {
        total[index]++;
        used = Math.max(used, index + 1);
    }

    Series newSeries() {
        Series created = new Series();
        series.add(created);
        return created;
    }

    long binUs() {
        return TimeUnit.NANOSECONDS.toMicros(widthNanos);
    }

    long[] counts() {
        return Arrays.copyOf(total, Math.max(1, used));
    }

    private void widen() {
        widthNanos *= 2;
        long[] next = new long[MAX_BINS];
        for (int i = 0; i < used; i++) next[i / 2] += total[i];
        total = next;
        used = (used + 1) / 2;
        for (Series endpointSeries : series) endpointSeries.halve();
    }

    /** One endpoint's counts, covering buckets {@code from .. from+len-1}. */
    static class Series {
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

        private void halve() {
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
}
