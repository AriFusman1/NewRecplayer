package com.pcapplayer.replay;

import com.pcapplayer.extract.ConnectionMode;
import com.pcapplayer.replay.transport.Transport;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * All running sessions (in memory; the app runs as a single replica). Guarantees that no two
 * running items play to the same target IP:port or listen on the same port.
 */
public final class ReplayEngine {

    public record Settings(int connectTimeoutMs) {
    }

    private final ScriptSource source;
    private final ReplayListener listener;
    private final Settings settings;
    private final Map<String, ReplaySession> sessions = new LinkedHashMap<>(); // guarded by this
    private final Map<String, ReplaySession> locks = new HashMap<>();          // guarded by this

    public ReplayEngine(ScriptSource source, ReplayListener listener, Settings settings) {
        this.source = source;
        this.listener = listener;
        this.settings = settings;
        NetWarmup.run();
    }

    ScriptSource source() {
        return source;
    }

    ReplayListener listener() {
        return listener;
    }

    Settings settings() {
        return settings;
    }

    /**
     * Starts a session. The first item's target is reserved (and for TCP_CLIENT its port bound)
     * before this returns, so conflicts are reported to the caller right away.
     *
     * @throws ReplayFailure TARGET_BUSY, LISTEN_FAILED, RECORD_NOT_FOUND, INVALID_TARGET
     */
    public SessionView start(List<ItemRequest> requests, boolean playlist, String startedBy) {
        if (requests.isEmpty()) throw new ReplayFailure("EMPTY_PLAYLIST", Map.of(), "Nothing to play");
        List<ReplaySession.Item> items = toItems(requests);
        ReplaySession session = new ReplaySession(this, playlist, startedBy, items);
        ReplaySession.Item first = session.firstItem();
        String key = acquire(first.info.mode(), first.target, session);
        Transport t = null;
        try {
            if (first.info.mode() == ConnectionMode.TCP_CLIENT) t = session.createTransport(first);
        } catch (ReplayFailure f) {
            release(key, session);
            throw f;
        }
        session.preAcquired(key, t);
        synchronized (this) {
            sessions.put(session.id, session);
        }
        Thread thread = new Thread(session, "replay-" + session.id.substring(0, 8));
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
        changed();
        return session.view();
    }

    public void stop(String sessionId, String by) {
        ReplaySession s;
        synchronized (this) {
            s = sessions.get(sessionId);
        }
        if (s == null) throw new ReplayFailure("SESSION_NOT_FOUND", Map.of(), "No such running replay");
        s.stop(by);
    }

    /** Edits a running playlist: everything after the current item is replaced. */
    public SessionView updateUpcoming(String sessionId, Long currentSleepAfterMs, List<ItemRequest> upcoming) {
        ReplaySession s;
        synchronized (this) {
            s = sessions.get(sessionId);
        }
        if (s == null) throw new ReplayFailure("SESSION_NOT_FOUND", Map.of(), "No such running replay");
        s.replaceUpcoming(currentSleepAfterMs, toItems(upcoming));
        changed();
        return s.view();
    }

    public List<SessionView> views() {
        List<ReplaySession> list;
        synchronized (this) {
            list = new ArrayList<>(sessions.values());
        }
        list.sort(Comparator.comparingLong(s -> s.startedAtMs));
        return list.stream().map(ReplaySession::view).toList();
    }

    public synchronized boolean anyRunning() {
        return !sessions.isEmpty();
    }

    /** Stops everything (shutdown). */
    public void stopAll(String by) {
        List<ReplaySession> list;
        synchronized (this) {
            list = new ArrayList<>(sessions.values());
        }
        list.forEach(s -> s.stop(by));
    }

    private List<ReplaySession.Item> toItems(List<ItemRequest> requests) {
        List<ReplaySession.Item> items = new ArrayList<>();
        for (ItemRequest r : requests) {
            ScriptSource.RecordInfo info = source.describe(r.recordId());
            validateTarget(info.mode(), r.target());
            items.add(new ReplaySession.Item(UUID.randomUUID().toString(), info, r.target(), Math.max(0, r.sleepAfterMs())));
        }
        return items;
    }

    private static void validateTarget(ConnectionMode mode, Target t) {
        if (t == null || t.port() < 1 || t.port() > 65535) {
            throw new ReplayFailure("INVALID_TARGET", Map.of(), "Port must be 1-65535");
        }
        if (mode != ConnectionMode.TCP_CLIENT && (t.host() == null || t.host().isBlank())) {
            throw new ReplayFailure("INVALID_TARGET", Map.of(), "Target IP is required");
        }
    }

    // ------------------------------------------------------------------ locks

    String acquire(ConnectionMode mode, Target target, ReplaySession owner) {
        String key = lockKey(mode, target);
        synchronized (this) {
            ReplaySession holder = locks.get(key);
            if (holder != null && holder != owner) {
                throw new ReplayFailure("TARGET_BUSY", Map.of("target", target.describe(mode), "by", holder.startedBy),
                        target.describe(mode) + " is already in use by a replay started by " + holder.startedBy);
            }
            locks.put(key, owner);
        }
        return key;
    }

    synchronized void release(String key, ReplaySession owner) {
        if (key != null) locks.remove(key, owner);
    }

    private static String lockKey(ConnectionMode mode, Target target) {
        if (mode == ConnectionMode.TCP_CLIENT) return "listen:" + target.port();
        String host = target.host().trim();
        try {
            host = InetAddress.getByName(host).getHostAddress();
        } catch (UnknownHostException e) {
            throw new ReplayFailure("INVALID_TARGET", Map.of("target", host), "Unknown host " + host);
        }
        return host + ":" + target.port();
    }

    // ------------------------------------------------------------------ callbacks from sessions

    void changed() {
        listener.sessionsChanged();
    }

    void finished(ReplaySession s) {
        synchronized (this) {
            sessions.remove(s.id);
            locks.values().removeIf(v -> v == s);
        }
        changed();
    }
}
