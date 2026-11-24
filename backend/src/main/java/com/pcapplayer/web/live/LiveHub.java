package com.pcapplayer.web.live;

import com.pcapplayer.common.Json;
import com.pcapplayer.playback.ReplayEngine;
import com.pcapplayer.playback.model.HistoryEntry;
import com.pcapplayer.playback.model.ReplayListener;
import io.vertx.core.Vertx;
import io.vertx.core.http.ServerWebSocket;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Plain WebSocket fan-out at {@code /ws}. Server → client JSON messages:
 * <ul>
 *   <li>{@code {type:"sessions", sessions:[...], active, max}} — on change and every 200 ms while anything
 *       plays; {@code active}/{@code max} is the global limit on running replays</li>
 *   <li>{@code {type:"notice", level, code, params, sessionId, startedBy}} — popups</li>
 *   <li>{@code {type:"catalog"}} / {@code {type:"history"}} — refetch hints</li>
 * </ul>
 */
public class LiveHub implements ReplayListener {

    private static final long TICK_MS = 200;

    private final Set<ServerWebSocket> sockets = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private final Consumer<HistoryEntry> historySink;
    private final AtomicReference<ReplayEngine> engine = new AtomicReference<>();

    public LiveHub(Consumer<HistoryEntry> historySink) {
        this.historySink = historySink;
    }

    public void start(Vertx vertx, ReplayEngine engine) {
        this.engine.set(engine);

        vertx.setPeriodic(TICK_MS, id -> {
            if (dirty.getAndSet(false) || engine.anyRunning()) pushSessions();
        });
    }

    public void accept(ServerWebSocket socket) {
        sockets.add(socket);
        socket.closeHandler(ignored -> sockets.remove(socket));
        socket.exceptionHandler(e -> sockets.remove(socket));
        socket.textMessageHandler(message -> { /* clients only listen */ });
        if (engine.get() != null) socket.writeTextMessage(sessionsMessage());
    }

    public void catalogChanged() {
        broadcast(Json.string(Map.of("type", "catalog")));
    }

    private void pushSessions() {
        if (sockets.isEmpty()) return;
        broadcast(sessionsMessage());
    }

    private String sessionsMessage() {
        ReplayEngine replays = engine.get();
        return Json.string(Map.of("type", "sessions", "sessions", replays.views(),
                "active", replays.activeReplays(), "max", replays.maxActiveReplays()));
    }

    private void broadcast(String text) {
        for (ServerWebSocket socket : sockets) {
            if (!socket.isClosed()) socket.writeTextMessage(text);
        }
    }

    // ------------------------------------------------------------------ ReplayListener

    @Override
    public void sessionsChanged() {
        dirty.set(true);
    }

    @Override
    public void notice(String level, String code, Map<String, Object> params, String sessionId, String startedBy) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", "notice");
        envelope.put("level", level);
        envelope.put("code", code);
        envelope.put("params", params);
        envelope.put("sessionId", sessionId);
        envelope.put("startedBy", startedBy);
        broadcast(Json.string(envelope));
    }

    @Override
    public void itemFinished(HistoryEntry entry) {
        historySink.accept(entry);
        broadcast(Json.string(Map.of("type", "history")));
        dirty.set(true);
    }
}
