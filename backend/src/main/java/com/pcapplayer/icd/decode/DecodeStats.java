package com.pcapplayer.icd.decode;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** Counts of what one decode run produced and what it could not decode. Logs problems, rate-limited. */
public final class DecodeStats {

    private static final RateLimitedLog LOG = new RateLimitedLog(Logger.getLogger(DecodeStats.class.getName()), 10, TimeUnit.SECONDS);

    /**
     * @param problems     how often each problem happened
     * @param skippedBytes bytes not decoded because of those problems (still sent unchanged)
     */
    public record Summary(long messages, Map<EDecodeProblem, Long> problems, long skippedBytes) {
    }

    private final String icdId;
    private final Map<EDecodeProblem, Long> problems = new EnumMap<>(EDecodeProblem.class);
    private long messages;
    private long skippedBytes;

    public DecodeStats(String icdId) {
        this.icdId = icdId;
    }

    void message() {
        messages++;
    }

    /**
     * Counts a problem.
     *
     * @param skipped bytes that were not decoded because of it
     * @param where   flow and position, for the log
     */
    void problem(EDecodeProblem problem, int skipped, String where, String detail) {
        problems.merge(problem, 1L, Long::sum);
        skippedBytes += skipped;
        LOG.warn(icdId + ":" + problem, () -> "ICD " + icdId + ": " + problem + " in " + where + ": " + detail
                + " (" + skipped + " bytes passed through without decoding)");
    }

    public long messages() {
        return messages;
    }

    public long count(EDecodeProblem problem) {
        return problems.getOrDefault(problem, 0L);
    }

    public long skippedBytes() {
        return skippedBytes;
    }

    public Summary summary() {
        return new Summary(messages, new LinkedHashMap<>(problems), skippedBytes);
    }
}
