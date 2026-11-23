package com.pcapplayer.replay;

import com.pcapplayer.pcap.script.EConnectionMode;
import com.pcapplayer.replay.model.HistoryEntry;
import com.pcapplayer.replay.model.ScriptSource;
import com.pcapplayer.replay.model.SessionView;
import com.pcapplayer.replay.model.Target;
import com.pcapplayer.replay.transport.TcpConnectTransport;
import com.pcapplayer.replay.transport.TcpListenTransport;
import com.pcapplayer.replay.transport.Transport;
import com.pcapplayer.replay.transport.UdpSendTransport;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Runs one or more items in order on its own thread. A single replay is a session with one item;
 * a playlist has several, with a sleep after each. Upcoming items can be edited while it runs; the
 * current item is locked. Stop ends the whole session.
 */
public class ReplaySession implements Runnable {

    private static final int MAX_LOG = 50;
    /** The sleep between items is taken in slices this long, so edits to its length apply at once. */
    private static final long SLEEP_SLICE_MS = 100;

    static class Item {
        final String itemId;
        final ScriptSource.RecordInfo info;
        final Target target;
        final AtomicLong sleepAfterMs;
        final AtomicReference<EItemStatus> status = new AtomicReference<>(EItemStatus.PENDING);

        Item(String itemId, ScriptSource.RecordInfo info, Target target, long sleepAfterMs) {
            this.itemId = itemId;
            this.info = info;
            this.target = target;
            this.sleepAfterMs = new AtomicLong(sleepAfterMs);
        }
    }

    final String id = UUID.randomUUID().toString();
    final boolean playlist;
    final String startedBy;
    final long startedAtMs = System.currentTimeMillis();

    private final ReplayEngine engine;
    /** Guards {@link #items}, {@link #current}, {@link #version} and {@link #log}. */
    private final ReentrantLock lock = new ReentrantLock();
    private final List<Item> items;
    private int current = -1;
    private int version;
    private final ArrayDeque<SessionView.LogLine> log = new ArrayDeque<>();

    private final AtomicReference<EItemState> state = new AtomicReference<>(EItemState.PREPARING);
    private final AtomicLong sleepUntilMs = new AtomicLong();
    private final AtomicReference<ItemProgress> progress = new AtomicReference<>();
    private final AtomicReference<ScriptSource.Prepared> prepared = new AtomicReference<>();
    private final AtomicReference<Control> control = new AtomicReference<>(new Control());
    private final AtomicReference<String> stoppedBy = new AtomicReference<>();
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

    private <T> T locked(Supplier<T> work) {
        lock.lock();
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }

    private void locked(Runnable work) {
        locked(() -> {
            work.run();
            return null;
        });
    }

    private boolean stopRequested() {
        return stoppedBy.get() != null;
    }

    Item firstItem() {
        return locked(() -> items.get(0));
    }

    // ------------------------------------------------------------------ control from other threads

    void stop(String requestedBy) {
        stoppedBy.compareAndSet(null, requestedBy == null ? "" : requestedBy);
        control.get().stop(requestedBy);
    }

    /**
     * Jumps the item being played to {@code positionUs} from its range start.
     *
     * @throws ReplayFailure SEEK_NOT_PLAYING if nothing is playing right now (connecting, waiting, sleeping)
     */
    void seek(long positionUs, String requestedBy) {
        ItemProgress ip = progress.get();
        if (state.get() != EItemState.PLAYING || ip == null) {
            throw new ReplayFailure("SEEK_NOT_PLAYING", Map.of(), "Jumping is possible only while playing");
        }
        long targetNanos = Math.max(0, Math.min(TimeUnit.MICROSECONDS.toNanos(positionUs), ip.durationNanos()));
        double targetSeconds = targetNanos / (double) TimeUnit.SECONDS.toNanos(1);
        log("info", "LOG_SEEK", Map.of("by", requestedBy == null ? "" : requestedBy,
                "to", String.format(Locale.ROOT, "%.1f", targetSeconds)));
        control.get().seek(targetNanos);
    }

    /**
     * Replaces everything after the current item.
     *
     * @param currentSleepAfterMs new sleep after the current item, or null to keep it
     */
    void replaceUpcoming(Long currentSleepAfterMs, List<Item> upcoming) {
        locked(() -> {
            int keep = Math.max(current + 1, 0);
            while (items.size() > keep) items.remove(items.size() - 1);
            items.addAll(upcoming);
            if (currentSleepAfterMs != null && current >= 0) items.get(current).sleepAfterMs.set(currentSleepAfterMs);
            version++;
        });
    }

    SessionView view() {
        return locked(this::viewLocked);
    }

    private SessionView viewLocked() {
        List<SessionView.Item> list = new ArrayList<>();
        for (Item item : items) {
            list.add(new SessionView.Item(item.itemId, item.info.recordId(), item.info.name(), item.info.categoryName(),
                    item.info.mode().name(), item.target.host(), item.target.port(), item.sleepAfterMs.get(),
                    item.status.get().name()));
        }
        SessionView.Progress progressView = null;
        ItemProgress ip = progress.get();
        ScriptSource.Prepared preparedNow = prepared.get();
        EItemState stateNow = state.get();
        if (ip != null && preparedNow != null && stateNow != EItemState.SLEEPING) {
            progressView = new SessionView.Progress(preparedNow.rangeStartUs(),
                    TimeUnit.NANOSECONDS.toMicros(ip.durationNanos()), TimeUnit.NANOSECONDS.toMicros(ip.positionNanos()),
                    ip.sentMessages(), ip.totalMessages(), ip.sentBytes(),
                    TimeUnit.NANOSECONDS.toMicros(preparedNow.histogram().binNanos()), preparedNow.histogram().counts());
        }
        long sleepLeft = stateNow == EItemState.SLEEPING ? Math.max(0, sleepUntilMs.get() - System.currentTimeMillis()) : 0;
        return new SessionView(id, playlist, startedBy, startedAtMs, version, stateNow.name(), current, list, progressView,
                sleepLeft, new ArrayList<>(log));
    }

    // ------------------------------------------------------------------ the session thread

    @Override
    public void run() {
        try {
            for (int index = 0; !stopRequested(); index++) {
                Item item = makeCurrent(index);
                if (item == null) break;
                playItem(item, index);
                boolean hasNext = itemAfter(index) != null;
                if (hasNext && !stopRequested() && item.sleepAfterMs.get() > 0) sleepBetweenItems(item);
            }
        } finally {
            engine.finished(this);
        }
    }

    /** Marks the item at {@code index} as the one playing, or returns null when the list has ended. */
    private Item makeCurrent(int index) {
        return locked(() -> {
            if (index >= items.size()) return null;
            current = index;
            Item item = items.get(index);
            item.status.set(EItemStatus.PLAYING);
            version++;
            return item;
        });
    }

    /** A new Control bound to this thread, so Stop reaches whatever runs next. */
    private Control freshControl() {
        Control fresh = new Control();
        fresh.bind(Thread.currentThread());
        control.set(fresh);
        return fresh;
    }

    private void sleepBetweenItems(Item item) {
        Control sleepControl = freshControl();
        if (stopRequested()) return;
        state.set(EItemState.SLEEPING);
        progress.set(null);
        // sleepAfterMs may be edited while sleeping, so re-read it each slice
        long start = System.currentTimeMillis();
        engine.changed();
        try {
            while (true) {
                long until = start + item.sleepAfterMs.get();
                sleepUntilMs.set(until);
                long left = until - System.currentTimeMillis();
                if (left <= 0) break;
                long sliceNanos = TimeUnit.MILLISECONDS.toNanos(Math.min(left, SLEEP_SLICE_MS));
                PreciseClock.sleepUntil(System.nanoTime() + sliceNanos, sleepControl);
            }
        } catch (StoppedException e) {
            // stop ends the session; run() sees stoppedBy
        }
        Thread.interrupted();
    }

    /** How an item ended. {@code failure} is set only for FAILED. */
    private record Outcome(EItemStatus result, ReplayFailure failure) {
        static final Outcome COMPLETED = new Outcome(EItemStatus.COMPLETED, null);
        static final Outcome STOPPED = new Outcome(EItemStatus.STOPPED, null);

        static Outcome failed(ReplayFailure failure) {
            return new Outcome(EItemStatus.FAILED, failure);
        }
    }

    private void playItem(Item item, int index) {
        Control itemControl = freshControl();
        String stopper = stoppedBy.get();
        if (stopper != null) itemControl.stop(stopper);
        long started = System.currentTimeMillis();
        state.set(EItemState.PREPARING);
        progress.set(null);
        prepared.set(null);
        engine.changed();

        Outcome outcome = playScript(item, index, itemControl);

        item.status.set(outcome.result());
        ReplayFailure failure = outcome.failure();
        if (failure != null) {
            log("error", failure.code(), failure.params());
            engine.listener().notice("error", failure.code(), failure.params(), id, startedBy);
        }
        int total = locked(() -> {
            version++;
            return items.size();
        });
        engine.listener().itemFinished(historyEntry(item, index, total, started, outcome));
    }

    /** Takes the target, prepares the script and plays it; always releases the target and transport. */
    private Outcome playScript(Item item, int index, Control itemControl) {
        Transport transport = null;
        String lockKey = null;
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
            ScriptSource.Prepared loaded = engine.source().prepare(item.info.recordId());
            itemControl.checkAlive();
            prepared.set(loaded);
            prefetchNext(index);
            ItemProgress itemProgress = new ItemProgress(loaded.script().durationNanos(), loaded.script().messageCount());
            progress.set(itemProgress);
            ItemPlayer.play(loaded.script(), transport, itemControl, itemProgress, newState -> {
                state.set(newState);
                engine.changed();
            }, this::log);
            return Outcome.COMPLETED;
        } catch (RuntimeException e) {
            return outcomeOf(e, itemControl);
        } finally {
            if (transport != null) transport.close();
            engine.release(lockKey, this);
            Thread.interrupted();
        }
    }

    /** Starts preparing the item after {@code index}, if any, while this one plays. */
    private void prefetchNext(int index) {
        Item next = itemAfter(index);
        if (next != null) engine.prefetch(next.info.recordId());
    }

    private Item itemAfter(int index) {
        return locked(() -> index + 1 < items.size() ? items.get(index + 1) : null);
    }

    /** A stop always reads as STOPPED, even if it surfaced as a socket error. */
    private static Outcome outcomeOf(RuntimeException e, Control itemControl) {
        if (e instanceof StoppedException || itemControl.stopped()) return Outcome.STOPPED;
        ReplayFailure failure = e instanceof ReplayFailure f ? f
                : new ReplayFailure("INTERNAL_ERROR", ReplayFailure.reason(e), e.toString());
        return Outcome.failed(failure);
    }

    private HistoryEntry historyEntry(Item item, int index, int total, long startedAtMs, Outcome outcome) {
        ItemProgress ip = progress.get();
        long sent = ip == null ? 0 : ip.sentMessages();
        long totalMessages = ip == null ? 0 : ip.totalMessages();
        long bytes = ip == null ? 0 : ip.sentBytes();
        ReplayFailure failure = outcome.failure();
        return new HistoryEntry(UUID.randomUUID().toString(), item.info.recordId(),
                item.info.name(), item.info.categoryName(), item.info.mode().name(),
                item.target.describe(item.info.mode()), startedBy, startedAtMs, System.currentTimeMillis(),
                sent, totalMessages, bytes,
                outcome.result().name(), outcome.result() == EItemStatus.STOPPED ? stoppedBy.get() : null,
                failure == null ? null : failure.code(), failure == null ? null : failure.params(),
                playlist ? new HistoryEntry.PlaylistRef(id, index + 1, total) : null);
    }

    Transport createTransport(Item item) {
        EConnectionMode mode = item.info.mode();
        return switch (mode) {
            case TCP_SERVER -> new TcpConnectTransport(item.target.host(), item.target.port(), engine.settings().connectTimeoutMs());
            case UDP_SERVER -> new UdpSendTransport(item.target.host(), item.target.port());
            case TCP_CLIENT -> {
                TcpListenTransport listener = new TcpListenTransport(item.target.port());
                listener.bind();
                yield listener;
            }
        };
    }

    void log(String level, String code, Map<String, Object> params) {
        SessionView.LogLine line = new SessionView.LogLine(System.currentTimeMillis(), level, code, params);
        locked(() -> {
            log.addLast(line);
            while (log.size() > MAX_LOG) log.removeFirst();
        });
    }
}
