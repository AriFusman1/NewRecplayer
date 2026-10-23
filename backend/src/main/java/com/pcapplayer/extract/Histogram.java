package com.pcapplayer.extract;

/** Messages per time slice across the range, for the live bar graph. */
public record Histogram(long binNanos, long[] counts) {

    /** One bar per second for short ranges, widened so there are never more than {@code maxBins}. */
    public static Histogram of(ReplayScript script, int maxBins) {
        long dur = Math.max(1, script.durationNanos());
        int bins = (int) Math.max(1, Math.min(maxBins, (dur + 999_999_999L) / 1_000_000_000L));
        long binNanos = Math.max(1, (dur + bins - 1) / bins);
        long[] counts = new long[bins];
        for (ScriptStep s : script.steps()) {
            if (s instanceof ScriptStep.Send) {
                int i = (int) Math.min(bins - 1, Math.max(0, s.offsetNanos() / binNanos));
                counts[i]++;
            }
        }
        return new Histogram(binNanos, counts);
    }
}
