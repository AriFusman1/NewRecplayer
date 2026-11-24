package com.pcapplayer.playback;

import com.pcapplayer.pcapProcessing.script.EConnectionMode;
import com.pcapplayer.playback.model.ItemRequest;
import com.pcapplayer.playback.model.ReplayListener;
import com.pcapplayer.playback.model.ScriptSource;
import com.pcapplayer.playback.model.SessionView;
import com.pcapplayer.playback.model.Target;
import com.pcapplayer.playback.transport.Transport;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * All running sessions (in memory; the app runs as a single replica). Guarantees that no two
 * running items play to the same target IP:port or listen on the same port.
 */
public class ReplayEngine {

    public record Settings(int connectTimeoutMs) {
    }

    private static final int PREFETCH_THREADS = 2;

    private final ScriptSource source;
    private final ReplayListener listener;
    private final Settings settings;
    private final ConcurrentMap<String, ReplaySession> sessions = new ConcurrentHashMap<>();

    /** Reserved targets ("ip:port" or "listen:port") and the session holding each. */
    private final ConcurrentMap<String, ReplaySession> locks = new ConcurrentHashMap<>();

    /** Low-priority threads that prepare upcoming playlist items ahead of time (warming the script cache). */
    private final ExecutorService prefetcher = Executors.newFixedThreadPool(PREFETCH_THREADS, task -> {
        Thread thread = new Thread(task, "replay-prefetch");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

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
        ReplaySession session = new ReplaySession(this, playlist, startedBy, toItems(requests));
        reserveFirstItem(session);
        launch(session);
        return session.view();
    }

    /**
     * "Play together": one single replay per request, all starting at the same moment. Every
     * target is reserved first; if any of them cannot be, nothing starts and the error names the
     * record ({@code params.record}).
     *
     * @throws ReplayFailure GROUP_SIZE, DUPLICATE_TARGET, TARGET_BUSY, LISTEN_FAILED, RECORD_NOT_FOUND, INVALID_TARGET
     */
    public List<SessionView> startGroup(List<ItemRequest> requests, String startedBy) {
        if (requests.isEmpty() || requests.size() > ReplayGroup.MAX_SIZE) {
            throw new ReplayFailure("GROUP_SIZE", Map.of("max", ReplayGroup.MAX_SIZE),
                    "Choose between 1 and " + ReplayGroup.MAX_SIZE + " records to play together");
        }
        List<ReplaySession> members = new ArrayList<>();
        Set<String> targets = new HashSet<>();

        try {
            for (ItemRequest request : requests) {
                ReplaySession session = new ReplaySession(this, false, startedBy, toItems(List.of(request)));
                SessionItem item = session.firstItem();

                try {
                    if (!targets.add(lockKey(item.info.mode(), item.target))) {
                        String target = item.target.describe(item.info.mode());
                        throw new ReplayFailure("DUPLICATE_TARGET", Map.of("target", target),
                                target + " is chosen for more than one record");
                    }
                    reserveFirstItem(session);
                } catch (ReplayFailure failure) {
                    throw naming(failure, item.info.name());
                }
                members.add(session);
            }
        } catch (RuntimeException e) {
            members.forEach(ReplaySession::releasePreAcquired); // all or nothing
            throw e;
        }
        ReplayGroup group = new ReplayGroup(members.size());
        members.forEach(session -> session.joinGroup(group));
        members.forEach(this::launch);
        return members.stream().map(ReplaySession::view).toList();
    }

    /** Reserves the first item's target (and binds its listen port for TCP_CLIENT) for {@code session}. */
    private void reserveFirstItem(ReplaySession session) {
        SessionItem first = session.firstItem();
        String key = acquire(first.info.mode(), first.target, session);
        Transport transport = null;

        try {
            if (first.info.mode() == EConnectionMode.TCP_CLIENT) transport = session.createTransport(first);
        } catch (ReplayFailure failure) {
            release(key, session);
            throw failure;
        }
        session.preAcquired(key, transport);
    }

    private void launch(ReplaySession session) {
        sessions.put(session.id, session);
        Thread thread = new Thread(session, "replay-" + session.id.substring(0, 8));
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
        changed();
    }

    /** The same failure with the record's name added, so the UI can say which record could not start. */
    private static ReplayFailure naming(ReplayFailure failure, String recordName) {
        Map<String, Object> params = new HashMap<>(failure.params());
        params.put("record", recordName);
        return new ReplayFailure(failure.code(), params, recordName + ": " + failure.getMessage());
    }

    public void stop(String sessionId, String requestedBy) {
        require(sessionId).stop(requestedBy);
    }

    /** Jumps a session's current item to another position (µs from its range start). */
    public void seek(String sessionId, long positionUs, String requestedBy) {
        require(sessionId).seek(positionUs, requestedBy);
        changed();
    }

    /** Edits a running playlist: everything after the current item is replaced. */
    public SessionView updateUpcoming(String sessionId, Long currentSleepAfterMs, List<ItemRequest> upcoming) {
        ReplaySession session = require(sessionId);
        session.replaceUpcoming(currentSleepAfterMs, toItems(upcoming));
        changed();
        return session.view();
    }

    public List<SessionView> views() {
        List<ReplaySession> list = snapshot();
        list.sort(Comparator.comparingLong(session -> session.startedAtMs));
        return list.stream().map(ReplaySession::view).toList();
    }

    public boolean anyRunning() {
        return !sessions.isEmpty();
    }

    /** Stops everything (shutdown). */
    public void stopAll(String requestedBy) {
        snapshot().forEach(session -> session.stop(requestedBy));
    }

    private ReplaySession require(String sessionId) {
        ReplaySession session = sessions.get(sessionId);
        if (session == null) throw new ReplayFailure("SESSION_NOT_FOUND", Map.of(), "No such running replay");
        return session;
    }

    private List<ReplaySession> snapshot() {
        return new ArrayList<>(sessions.values());
    }

    private List<SessionItem> toItems(List<ItemRequest> requests) {
        List<SessionItem> items = new ArrayList<>();

        for (ItemRequest request : requests) {
            ScriptSource.RecordInfo info = source.describe(request.recordId());
            validateTarget(info.mode(), request.target());
            items.add(new SessionItem(UUID.randomUUID().toString(), info, request.target(), Math.max(0, request.sleepAfterMs())));
        }
        return items;
    }

    private static void validateTarget(EConnectionMode mode, Target target) {
        if (target == null || target.port() < 1 || target.port() > 65535) {
            throw new ReplayFailure("INVALID_TARGET", Map.of(), "Port must be 1-65535");
        }

        if (mode != EConnectionMode.TCP_CLIENT && (target.host() == null || target.host().isBlank())) {
            throw new ReplayFailure("INVALID_TARGET", Map.of(), "Target IP is required");
        }
    }

    // ------------------------------------------------------------------ locks

    String acquire(EConnectionMode mode, Target target, ReplaySession owner) {
        String key = lockKey(mode, target);
        // atomic check-and-reserve; throwing leaves the map unchanged
        locks.compute(key, (reserved, holder) -> {
            if (holder != null && holder != owner) {
                throw new ReplayFailure("TARGET_BUSY", Map.of("target", target.describe(mode), "by", holder.startedBy),
                        target.describe(mode) + " is already in use by a replay started by " + holder.startedBy);
            }
            return owner;
        });
        return key;
    }

    void release(String key, ReplaySession owner) {
        if (key != null) locks.remove(key, owner);
    }

    private static String lockKey(EConnectionMode mode, Target target) {
        if (mode == EConnectionMode.TCP_CLIENT) return "listen:" + target.port();
        String host = target.host().trim();

        try {
            host = InetAddress.getByName(host).getHostAddress();
        } catch (UnknownHostException e) {
            throw new ReplayFailure("INVALID_TARGET", Map.of("target", host), "Unknown host " + host);
        }
        return host + ":" + target.port();
    }

    // ------------------------------------------------------------------ callbacks from sessions

    /**
     * Prepares a record in the background so a playlist's next item starts without parsing.
     * Best effort: a failure here shows up again (and is reported) when the item actually starts.
     */
    void prefetch(String recordId) {
        prefetcher.execute(() -> {
            try {
                source.prepare(recordId);
            } catch (RuntimeException ignored) {
                // reported when the item is played
            }
        });
    }

    void changed() {
        listener.sessionsChanged();
    }

    void finished(ReplaySession session) {
        sessions.remove(session.id);
        locks.values().removeIf(holder -> holder == session);
        changed();
    }
}
