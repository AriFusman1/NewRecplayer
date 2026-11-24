package com.pcapplayer.playback.model;

import java.util.List;
import java.util.Map;

/** Snapshot of a running session for the UI (Now Playing + live view). */
public record SessionView(String id, boolean playlist, String startedBy, long startedAtMs, int version,
                          String state, int currentIndex, List<Item> items, Progress progress,
                          long sleepRemainingMs, List<LogLine> log, Group group) {

    /** Set when the session was started with others ("Play together"); {@code size} counts all of them. */
    public record Group(String id, int size) {
    }

    /** @param status PENDING | PLAYING | COMPLETED | FAILED | STOPPED */
    public record Item(String itemId, String recordId, String recordName, String categoryName, String mode,
                       String host, int port, long sleepAfterMs, String status) {
    }

    /** Progress of the current item; null while preparing or sleeping. */
    public record Progress(long rangeStartUs, long durationUs, long positionUs, long sentMessages,
                           long totalMessages, long sentBytes, long binUs, long[] histogram) {
    }

    public record LogLine(long tsMs, String level, String code, Map<String, Object> params) {
    }
}
