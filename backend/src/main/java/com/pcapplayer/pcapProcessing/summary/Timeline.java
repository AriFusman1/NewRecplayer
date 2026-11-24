package com.pcapplayer.pcapProcessing.summary;

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
    private final List<TimelineSeries> series = new ArrayList<>();

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

    TimelineSeries newSeries() {
        TimelineSeries created = new TimelineSeries();
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
        for (TimelineSeries endpointSeries : series) endpointSeries.halve();
    }
}
