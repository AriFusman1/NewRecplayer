package com.pcapplayer.replay.model;

import java.util.Map;

/**
 * One finished run of one record.
 *
 * @param result      COMPLETED | STOPPED | FAILED
 * @param failureCode set when FAILED (see {@link ReplayFailure})
 * @param playlist    null for a single replay
 */
public record HistoryEntry(String id, String recordId, String recordName, String categoryName, String mode,
                           String target, String startedBy, long startedAtMs, long endedAtMs,
                           long messagesSent, long totalMessages, long bytesSent,
                           String result, String stoppedBy, String failureCode, Map<String, Object> failureParams,
                           PlaylistRef playlist) {

    public record PlaylistRef(String sessionId, int index, int total) {
    }
}
