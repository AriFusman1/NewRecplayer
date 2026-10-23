package com.pcapplayer.api;

import com.pcapplayer.replay.HistoryEntry;
import com.pcapplayer.replay.ReplayEngine;
import com.pcapplayer.replay.ReplayListener;
import com.pcapplayer.util.Json;
import io.vertx.core.Vertx;
import io.vertx.core.http.ServerWebSocket;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Plain WebSocket fan-out at {@code /ws}. Server → client JSON messages:
 * <ul>
 *   <li>{@code {type:"sessions", sessions:[...]}} — on change and every 200 ms while anything plays</li>
 *   <li>{@code {type:"notice", level, code, params, sessionId, startedBy}} — popups</li>
 *   <li>{@code {type:"catalog"}} / {@code {type:"history"}} — refetch hints</li>
 * </ul>
 */
public final class LiveHub implements ReplayListener {

    private static final long TICK_MS = 200;

    private final Set<ServerWebSocket> sockets = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private final Consumer<HistoryEntry> historySink;
    private volatile ReplayEngine engine;

    public LiveHub(Consumer<HistoryEntry> historySink) {
        this.historySink = historySink;
    }

    public void start(Vertx vertx, ReplayEngine engine) {
        this.engine = engine;
        vertx.setPeriodic(TICK_MS, id -> {
            if (dirty.getAndSet(false) || engine.anyRunning()) pushSessions();
        });
    }

    public void accept(ServerWebSocket ws) {
        sockets.add(ws);
        ws.closeHandler(v -> sockets.remove(ws));
        ws.exceptionHandler(e -> sockets.remove(ws));
        ws.textMessageHandler(m -> { /* clients only listen */ });
        if (engine != null) ws.writeTextMessage(sessionsMessage());
    }

    public void catalogChanged() {
        broadcast(Json.string(Map.of("type", "catalog")));
    }

    private void pushSessions() {
        if (sockets.isEmpty()) return;
        broadcast(sessionsMessage());
    }

    private String sessionsMessage() {
        return Json.string(Map.of("type", "sessions", "sessions", engine.views()));
    }

    private void broadcast(String text) {
        for (ServerWebSocket ws : sockets) {
            if (!ws.isClosed()) ws.writeTextMessage(text);
        }
    }

    // ------------------------------------------------------------------ ReplayListener

    @Override
    public void sessionsChanged() {
        dirty.set(true);
    }

    @Override
    public void notice(String level, String code, Map<String, Object> params, String sessionId, String startedBy) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "notice");
        m.put("level", level);
        m.put("code", code);
        m.put("params", params);
        m.put("sessionId", sessionId);
        m.put("startedBy", startedBy);
        broadcast(Json.string(m));
    }

    @Override
    public void itemFinished(HistoryEntry entry) {
        historySink.accept(entry);
        broadcast(Json.string(Map.of("type", "history")));
        dirty.set(true);
    }
}
