package com.pcapplayer.extract;

import java.util.List;

/**
 * Everything a replay needs: ordered steps plus totals.
 *
 * @param durationNanos range length (range end − range start)
 */
public record ReplayScript(ConnectionMode mode, List<ScriptStep> steps, long messageCount, long totalBytes,
                           long durationNanos) {
}
