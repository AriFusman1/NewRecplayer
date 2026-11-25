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
import java.time.Clock;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;

/**
 * All running sessions (in memory; the app runs as a single replica). Guarantees that no two
 * running items play to the same target IP:port or listen on the same port.
 */
public class ReplayEngine {

    /**
     * @param maxActiveReplays      replays running at once, all users together
     * @param groupConnectTimeoutMs how long a group waits for its TCP clients to connect
     * @param tcpKeepTrying         TCP-server replays play without a connection and keep retrying it
     * @param tcpRetryIntervalMs    the retry interval
     * @param clock                 wall-clock time for time fixing (UTC); injectable for tests
     */
    public record Settings(int connectTimeoutMs, int maxActiveReplays, long groupConnectTimeoutMs, boolean tcpKeepTrying,
                           long tcpRetryIntervalMs, Clock clock) {

        private static final int DEFAULT_MAX_ACTIVE_REPLAYS = 10;
        private static final long DEFAULT_GROUP_CONNECT_TIMEOUT_MS = 300_000;
        private static final long DEFAULT_TCP_RETRY_INTERVAL_MS = 1_000;

        public Settings(int connectTimeoutMs, int maxActiveReplays, long groupConnectTimeoutMs, boolean tcpKeepTrying,
                        long tcpRetryIntervalMs) {
            this(connectTimeoutMs, maxActiveReplays, groupConnectTimeoutMs, tcpKeepTrying, tcpRetryIntervalMs, Clock.systemUTC());
        }

        /** Default limits, strict TCP (no keep-trying): for tests that only care about the clock. */
        public Settings(int connectTimeoutMs, Clock clock) {
            this(connectTimeoutMs, DEFAULT_MAX_ACTIVE_REPLAYS, DEFAULT_GROUP_CONNECT_TIMEOUT_MS, false, DEFAULT_TCP_RETRY_INTERVAL_MS, clock);
        }

        public Settings(int connectTimeoutMs) {
            this(connectTimeoutMs, Clock.systemUTC());
        }
    }

    private static final int PREFETCH_THREADS = 2;

    /** "Play together" needs at least this many recordings. */
    static final int MIN_GROUP_SIZE = 2;

    private final ScriptSource source;
    private final ReplayListener listener;
    private final Settings settings;
    private final ConcurrentMap<String, ReplaySession> sessions = new ConcurrentHashMap<>();

    /** Reserved targets ("ip:port" or "listen:port") and the session holding each. */
    private final ConcurrentMap<String, ReplaySession> locks = new ConcurrentHashMap<>();

    /** The global limit; a slot is taken when a replay starts and given back in {@link #finished}. */
    private final ActiveReplays activeReplays;

    /** Fires group connect timeouts. */
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "replay-group-timeouts");
        thread.setDaemon(true);
        return thread;
    });

    /** Low-priority threads that prepare upcoming playlist items ahead of time (warming the script cache). */
    private final ExecutorService prefetcher = Executors.newFixedThreadPool(PREFETCH_THREADS, task -> {
        Thread thread = new Thread(task, "replay-prefetch");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    /** Creates each session's thread; replaceable so tests can make thread creation fail. */
    private final ThreadFactory replayThreads;

    /** While shutting down, stop the replays again this often, in case one was starting just then. */
    private static final long SHUTDOWN_SLICE_MS = 50;

    public ReplayEngine(ScriptSource source, ReplayListener listener, Settings settings) {
        this(source, listener, settings, Thread::new);
    }

    ReplayEngine(ScriptSource source, ReplayListener listener, Settings settings, ThreadFactory replayThreads) {
        this.source = source;
        this.listener = listener;
        this.settings = settings;
        this.replayThreads = replayThreads;
        this.activeReplays = new ActiveReplays(settings.maxActiveReplays());
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
     * @throws ReplayFailure ACTIVE_LIMIT, TARGET_BUSY, LISTEN_FAILED, RECORD_NOT_FOUND, INVALID_TARGET,
     *                       START_FAILED, SHUTTING_DOWN
     */
    public SessionView start(List<ItemRequest> requests, boolean playlist, String startedBy) {
        return start(requests, playlist, null, startedBy);
    }

    /** @param playlistName the saved playlist being played, recorded in history; null for a draft or a single replay */
    public SessionView start(List<ItemRequest> requests, boolean playlist, String playlistName, String startedBy) {
        if (requests.isEmpty()) throw new ReplayFailure("EMPTY_PLAYLIST", Map.of(), "Nothing to play");
        ReplaySession session = new ReplaySession(this, playlist, playlistName, startedBy, toItems(requests));
        activeReplays.take(1); // a playlist plays one item at a time, so it takes one slot

        try {
            reserveFirstItem(session);
        } catch (RuntimeException | Error e) {
            activeReplays.release(1);
            throw e;
        }
        launch(session); // gives everything back itself if the thread cannot start
        return session.view();
    }

    /**
     * "Play together": one single replay per request, all starting at the same moment. When some of
     * them are TCP clients, nothing plays until every one of those has been connected to (see
     * {@link ReplayGroup}). Slots and targets are taken for all of them first; if any cannot be,
     * nothing starts, and a target error names the record ({@code params.record}).
     *
     * @throws ReplayFailure GROUP_TOO_SMALL (400), ACTIVE_LIMIT, DUPLICATE_TARGET, TARGET_BUSY, LISTEN_FAILED, RECORD_NOT_FOUND,
     *                       INVALID_TARGET, START_FAILED, SHUTTING_DOWN
     */
    public List<SessionView> startGroup(List<ItemRequest> requests, String startedBy) {
        if (requests.size() < MIN_GROUP_SIZE) { // one recording is played with Play, not Play together
            throw new ReplayFailure("GROUP_TOO_SMALL", Map.of("min", MIN_GROUP_SIZE, "requested", requests.size()),
                    "Select at least " + MIN_GROUP_SIZE + " recordings to play together");
        }
        activeReplays.take(requests.size()); // all or nothing
        List<ReplaySession> members = new ArrayList<>();
        Set<String> targets = new HashSet<>();
        ReplayGroup group;

        try {
            for (ItemRequest request : requests) {
                ReplaySession session = new ReplaySession(this, false, null, startedBy, toItems(List.of(request)));
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
            group = new ReplayGroup(members);
            members.forEach(session -> session.joinGroup(group));
            if (group.hasTcpClients()) scheduleConnectTimeout(group, startedBy, members.get(0).id);
        } catch (RuntimeException | Error e) {
            members.forEach(ReplaySession::releasePreAcquired); // all or nothing
            activeReplays.release(requests.size());
            throw e;
        }
        launchGroup(members, group);
        return members.stream().map(ReplaySession::view).toList();
    }

    /**
     * Starts every member's thread. If one cannot start, the group never will: members already
     * running end with GROUP_FAILED (each giving back its own slot), and the rest are given back here.
     */
    private void launchGroup(List<ReplaySession> members, ReplayGroup group) {
        for (int index = 0; index < members.size(); index++) {
            ReplaySession member = members.get(index);

            try {
                launch(member);
            } catch (ReplayFailure failure) {
                List<ReplaySession> notLaunched = members.subList(index + 1, members.size());
                notLaunched.forEach(ReplaySession::releasePreAcquired);
                activeReplays.release(notLaunched.size());
                group.memberEnded(member, null, failure);
                throw naming(failure, member.firstItem().info.name());
            }
        }
    }

    /** If the group is still waiting for its TCP clients when the timeout expires, it fails (announced once). */
    private void scheduleConnectTimeout(ReplayGroup group, String startedBy, String sessionId) {
        long timeoutMs = settings.groupConnectTimeoutMs();
        timers.schedule(() -> {
            ReplayFailure timedOut = group.timeOut(timeoutMs);
            if (timedOut != null) listener.notice("error", timedOut.code(), timedOut.params(), sessionId, startedBy);
        }, timeoutMs, TimeUnit.MILLISECONDS);
    }

    /** Replays running now (all users), including those waiting for a connection. */
    public int activeReplays() {
        return activeReplays.active();
    }

    /** The configured limit for {@link #activeReplays()}. */
    public int maxActiveReplays() {
        return activeReplays.max();
    }

    /** Reserves the first item's target (and binds its listen port for TCP_CLIENT) for {@code session}. */
    private void reserveFirstItem(ReplaySession session) {
        SessionItem first = session.firstItem();
        String key = acquire(first.info.mode(), first.target, session);
        Transport transport = null;

        try {
            if (first.info.mode() == EConnectionMode.TCP_CLIENT) transport = session.createTransport(first);
        } catch (RuntimeException | Error e) {
            release(key, session);
            throw e;
        }
        session.preAcquired(key, transport);
    }

    /**
     * Starts the session's thread, which from then on gives back its slot, targets and transport
     * when it ends ({@link #finished}). If the thread cannot start, that is done here instead.
     *
     * @throws ReplayFailure START_FAILED
     */
    private void launch(ReplaySession session) {
        sessions.put(session.id, session);

        try {
            Thread thread = replayThreads.newThread(session);
            thread.setName("replay-" + session.id.substring(0, 8));
            thread.setPriority(Thread.MAX_PRIORITY);
            thread.start();
        } catch (RuntimeException | Error e) { // e.g. OutOfMemoryError: unable to create native thread
            sessions.remove(session.id);
            session.releasePreAcquired();
            locks.values().removeIf(holder -> holder == session);
            activeReplays.release(1);
            changed();
            throw new ReplayFailure("START_FAILED", ReplayFailure.reason(e), "Could not start the replay thread: " + e);
        }
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

    /** Stops every running replay. */
    public void stopAll(String requestedBy) {
        snapshot().forEach(session -> session.stop(requestedBy));
    }

    /**
     * Server shutdown: refuses new replays, stops every running one and waits up to {@code graceMs}
     * for them to end (each writes its history entry and gives back its slot), then stops the
     * engine's own threads.
     *
     * @return true if every replay ended in time
     */
    public boolean shutdown(String requestedBy, long graceMs) {
        activeReplays.close();
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(graceMs);
        boolean idle = activeReplays.active() == 0;

        try {
            while (!idle && System.nanoTime() < end) {
                stopAll(requestedBy); // again each round: a replay that was just starting is stopped too
                long leftMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime()));
                idle = activeReplays.awaitIdle(Math.min(SHUTDOWN_SLICE_MS, leftMs));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        timers.shutdownNow();
        prefetcher.shutdownNow();
        return idle;
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

    /** Every launched session ends here exactly once (its thread's finally), whatever the reason. */
    void finished(ReplaySession session) {
        sessions.remove(session.id);
        locks.values().removeIf(holder -> holder == session);
        activeReplays.release(1);
        changed();
    }
}
