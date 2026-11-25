package com.pcapplayer.pcapProcessing.script;

import java.util.List;

/**
 * Everything a replay needs: ordered steps plus totals.
 *
 * @param durationNanos range length (range end − range start)
 * @param flows         the recorded flows the steps came from, indexed by their id
 */
public record ReplayScript(EConnectionMode mode, List<ScriptStep> steps, long messageCount, long totalBytes,
                           long durationNanos, List<ScriptFlow> flows) {

    /** A script whose flows are not tracked (tests). */
    public ReplayScript(EConnectionMode mode, List<ScriptStep> steps, long messageCount, long totalBytes, long durationNanos) {
        this(mode, steps, messageCount, totalBytes, durationNanos, List.of());
    }
}
