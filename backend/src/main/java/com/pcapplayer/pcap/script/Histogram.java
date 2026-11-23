package com.pcapplayer.pcap.script;

import java.util.concurrent.TimeUnit;

/** Messages per time slice across the range, for the live bar graph. */
public record Histogram(long binNanos, long[] counts) {

    private static final long NANOS_PER_SECOND = TimeUnit.SECONDS.toNanos(1);

    /** One bar per second for short ranges, widened so there are never more than {@code maxBins}. */
    public static Histogram of(ReplayScript script, int maxBins) {
        long durationNanos = Math.max(1, script.durationNanos());
        int bins = (int) Math.max(1, Math.min(maxBins, ceilDiv(durationNanos, NANOS_PER_SECOND)));
        long binNanos = Math.max(1, ceilDiv(durationNanos, bins));
        long[] counts = new long[bins];
        for (ScriptStep step : script.steps()) {
            if (step instanceof ScriptStep.Send) {
                int i = (int) Math.min(bins - 1, Math.max(0, step.offsetNanos() / binNanos));
                counts[i]++;
            }
        }
        return new Histogram(binNanos, counts);
    }

    /** {@code dividend / divisor} rounded up, for positive values (Math.ceilDiv needs Java 18). */
    private static long ceilDiv(long dividend, long divisor) {
        return (dividend + divisor - 1) / divisor;
    }
}
