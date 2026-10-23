package com.pcapplayer.replay;

import com.pcapplayer.extract.ConnectionMode;
import com.pcapplayer.replay.transport.TcpConnectTransport;
import com.pcapplayer.replay.transport.TcpListenTransport;
import com.pcapplayer.replay.transport.Transport;
import com.pcapplayer.replay.transport.UdpSendTransport;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runs one or more items in order on its own thread. A single replay is a session with one item;
 * a playlist has several, with a sleep after each. Upcoming items can be edited while it runs; the
 * current item is locked. Stop ends the whole session.
 */
final class ReplaySession implements Runnable {

    private static final int MAX_LOG = 50;

    static final class Item {
        final String itemId;
        final ScriptSource.RecordInfo info;
        final Target target;
        volatile long sleepAfterMs;
        volatile String status = "PENDING";

        Item(String itemId, ScriptSource.RecordInfo info, Target target, long sleepAfterMs) {
            this.itemId = itemId;
            this.info = info;
            this.target = target;
            this.sleepAfterMs = sleepAfterMs;
        }
    }

    final String id = UUID.randomUUID().toString();
    final boolean playlist;
    final String startedBy;
    final long startedAtMs = System.currentTimeMillis();

    private final ReplayEngine engine;
    private final List<Item> items;           // guarded by this
    private int current = -1;                 // guarded by this
    private int version;                      // guarded by this
    private final ArrayDeque<SessionView.LogLine> log = new ArrayDeque<>(); // guarded by this

    private volatile ItemState state = ItemState.PREPARING;
    private volatile long sleepUntilMs;
    private volatile ItemProgress progress;
    private volatile ScriptSource.Prepared prepared;
    private volatile Control control = new Control();
    private volatile String stoppedBy;
    /** The first item's lock and (for TCP_CLIENT, already bound) transport, taken by the engine up front. */
    private String firstLockKey;
    private Transport firstTransport;

    ReplaySession(ReplayEngine engine, boolean playlist, String startedBy, List<Item> items) {
        this.engine = engine;
        this.playlist = playlist;
        this.startedBy = startedBy;
        this.items = new ArrayList<>(items);
    }

    void preAcquired(String lockKey, Transport transport) {
        this.firstLockKey = lockKey;
        this.firstTransport = transport;
    }

    synchronized Item firstItem() {
        return items.get(0);
    }

    // ------------------------------------------------------------------ control from other threads

    void stop(String by) {
        if (stoppedBy == null) stoppedBy = by;
        control.stop(by);
    }

    /**
     * Replaces everything after the current item.
     *
     * @param currentSleepAfterMs new sleep after the current item, or null to keep it
     */
    synchronized void replaceUpcoming(Long currentSleepAfterMs, List<Item> upcoming) {
        int keep = Math.max(current + 1, 0);
        while (items.size() > keep) items.remove(items.size() - 1);
        items.addAll(upcoming);
        if (currentSleepAfterMs != null && current >= 0) items.get(current).sleepAfterMs = currentSleepAfterMs;
        version++;
    }

    synchronized SessionView view() {
        List<SessionView.Item> list = new ArrayList<>();
        for (Item it : items) {
            list.add(new SessionView.Item(it.itemId, it.info.recordId(), it.info.name(), it.info.categoryName(),
                    it.info.mode().name(), it.target.host(), it.target.port(), it.sleepAfterMs, it.status));
        }
        SessionView.Progress p = null;
        ItemProgress ip = progress;
        ScriptSource.Prepared pr = prepared;
        if (ip != null && pr != null && state != ItemState.SLEEPING) {
            p = new SessionView.Progress(pr.rangeStartUs(), ip.durationNanos() / 1000, ip.positionNanos() / 1000,
                    ip.sentMessages(), ip.totalMessages(), ip.sentBytes(),
                    pr.histogram().binNanos() / 1000, pr.histogram().counts());
        }
        long sleepLeft = state == ItemState.SLEEPING ? Math.max(0, sleepUntilMs - System.currentTimeMillis()) : 0;
        return new SessionView(id, playlist, startedBy, startedAtMs, version, state.name(), current, list, p,
                sleepLeft, new ArrayList<>(log));
    }

    // ------------------------------------------------------------------ the session thread

    @Override
    public void run() {
        try {
            int index = 0;
            while (stoppedBy == null) {
                Item item;
                synchronized (this) {
                    if (index >= items.size()) break;
                    current = index;
                    item = items.get(index);
                    item.status = "PLAYING";
                    version++;
                }
                playItem(item, index);
                Item next;
                synchronized (this) {
                    next = index + 1 < items.size() ? items.get(index + 1) : null;
                }
                if (next != null && stoppedBy == null && item.sleepAfterMs > 0) {
                    sleepBetweenItems(item);
                }
                index++;
            }
        } finally {
            engine.finished(this);
        }
    }

    private void sleepBetweenItems(Item item) {
        Control c = new Control();
        c.bind(Thread.currentThread());
        control = c;
        if (stoppedBy != null) return;
        state = ItemState.SLEEPING;
        progress = null;
        // sleepAfterMs may be edited while sleeping, so re-read it each slice
        long start = System.currentTimeMillis();
        engine.changed();
        try {
            while (true) {
                sleepUntilMs = start + item.sleepAfterMs;
                long left = sleepUntilMs - System.currentTimeMillis();
                if (left <= 0) break;
                PreciseClock.sleepUntil(System.nanoTime() + Math.min(left, 100) * 1_000_000, c);
            }
        } catch (StoppedException e) {
            // stop ends the session; run() sees stoppedBy
        }
        Thread.interrupted();
    }

    private void playItem(Item item, int index) {
        Control c = new Control();
        c.bind(Thread.currentThread());
        control = c;
        if (stoppedBy != null) c.stop(stoppedBy);
        long started = System.currentTimeMillis();
        String result = "COMPLETED";
        ReplayFailure failure = null;
        Transport transport = null;
        String lockKey = null;
        state = ItemState.PREPARING;
        progress = null;
        prepared = null;
        engine.changed();
        try {
            if (index == 0 && firstLockKey != null) {
                lockKey = firstLockKey;
                transport = firstTransport != null ? firstTransport : createTransport(item);
                firstLockKey = null;
                firstTransport = null;
            } else {
                lockKey = engine.acquire(item.info.mode(), item.target, this);
                transport = createTransport(item);
            }
            ScriptSource.Prepared pr = engine.source().prepare(item.info.recordId());
            c.checkAlive();
            prepared = pr;
            progress = new ItemProgress(pr.script().durationNanos(), pr.script().messageCount());
            ItemPlayer.play(pr.script(), transport, c, progress, s -> {
                state = s;
                engine.changed();
            }, this::log);
        } catch (StoppedException e) {
            result = "STOPPED";
        } catch (ReplayFailure f) {
            if (c.stopped()) {
                result = "STOPPED";
            } else {
                result = "FAILED";
                failure = f;
            }
        } catch (RuntimeException e) {
            result = c.stopped() ? "STOPPED" : "FAILED";
            if (!c.stopped()) failure = new ReplayFailure("INTERNAL_ERROR", Map.of("reason", String.valueOf(e.getMessage())), e.toString());
        } finally {
            if (transport != null) transport.close();
            engine.release(lockKey, this);
            Thread.interrupted();
        }

        item.status = result;
        if (failure != null) {
            log("error", failure.code(), failure.params());
            engine.listener().notice("error", failure.code(), failure.params(), id, startedBy);
        }
        ItemProgress ip = progress;
        int total;
        synchronized (this) {
            total = items.size();
            version++;
        }
        engine.listener().itemFinished(new HistoryEntry(UUID.randomUUID().toString(), item.info.recordId(),
                item.info.name(), item.info.categoryName(), item.info.mode().name(),
                item.target.describe(item.info.mode()), startedBy, started, System.currentTimeMillis(),
                ip == null ? 0 : ip.sentMessages(), ip == null ? 0 : ip.totalMessages(), ip == null ? 0 : ip.sentBytes(),
                result, "STOPPED".equals(result) ? stoppedBy : null,
                failure == null ? null : failure.code(), failure == null ? null : failure.params(),
                playlist ? new HistoryEntry.PlaylistRef(id, index + 1, total) : null));
    }

    Transport createTransport(Item item) {
        ConnectionMode mode = item.info.mode();
        return switch (mode) {
            case TCP_SERVER -> new TcpConnectTransport(item.target.host(), item.target.port(), engine.settings().connectTimeoutMs());
            case UDP_SERVER -> new UdpSendTransport(item.target.host(), item.target.port());
            case TCP_CLIENT -> {
                TcpListenTransport t = new TcpListenTransport(item.target.port());
                t.bind();
                yield t;
            }
        };
    }

    synchronized void log(String level, String code, Map<String, Object> params) {
        log.addLast(new SessionView.LogLine(System.currentTimeMillis(), level, code, params));
        while (log.size() > MAX_LOG) log.removeFirst();
    }
}
