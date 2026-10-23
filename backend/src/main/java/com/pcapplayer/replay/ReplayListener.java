package com.pcapplayer.replay;

import java.util.Map;

/** Receives engine events (implemented by the API layer: WebSocket broadcast, history storage). */
public interface ReplayListener {

    /** Something visible in Now Playing changed (state, items, a session started or ended). */
    void sessionsChanged();

    /**
     * A popup-worthy event. level: "error" | "warning" | "info".
     * {@code startedBy} lets the UI show a modal to the person who started the run.
     */
    void notice(String level, String code, Map<String, Object> params, String sessionId, String startedBy);

    void itemFinished(HistoryEntry entry);
}
