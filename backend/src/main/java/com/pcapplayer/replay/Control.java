package com.pcapplayer.replay;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Shared between a replay thread, its transport's helper threads and whoever presses Stop.
 * The replay thread calls {@link #checkAlive()} while it waits so stops and connection failures
 * surface promptly.
 */
public class Control {

    private final AtomicReference<String> stoppedBy = new AtomicReference<>();
    private final AtomicReference<ReplayFailure> failure = new AtomicReference<>();
    private final AtomicReference<Thread> owner = new AtomicReference<>();
    private final AtomicReference<AutoCloseable> resource = new AtomicReference<>();
    /** Requested playback position (ns from range start), or -1 if none is pending. */
    private final AtomicLong seekTo = new AtomicLong(-1);

    void bind(Thread thread) {
        owner.set(thread);
    }

    /** Closed on stop, so a replay thread blocked in a socket call wakes up. */
    public void closeOnAbort(AutoCloseable closeable) {
        resource.set(closeable);
        if (stopped()) abort();
    }

    public void stop(String requestedBy) {
        stoppedBy.compareAndSet(null, requestedBy == null ? "" : requestedBy);
        abort();
    }

    /** Reported by a transport helper thread (e.g. the peer disconnected). First failure wins. */
    public void fail(ReplayFailure reported) {
        failure.compareAndSet(null, reported);
        interruptOwner();
    }

    private void interruptOwner() {
        Thread thread = owner.get();
        if (thread != null) thread.interrupt();
    }

    private void abort() {
        interruptOwner();
        AutoCloseable closeable = resource.get();
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // closing only to unblock
            }
        }
    }

    /**
     * Asks the replay thread to continue from {@code positionNanos}. Wakes it with unpark, not
     * interrupt, so blocking socket calls are left alone; a newer request replaces a pending one.
     */
    void seek(long positionNanos) {
        seekTo.set(Math.max(0, positionNanos));
        Thread thread = owner.get();
        if (thread != null) LockSupport.unpark(thread);
    }

    boolean seekPending() {
        return seekTo.get() >= 0;
    }

    /** The pending position, cleared; -1 if none. */
    long takeSeek() {
        return seekTo.getAndSet(-1);
    }

    public boolean stopped() {
        return stoppedBy.get() != null;
    }

    public void checkAlive() {
        if (stopped()) throw new StoppedException();
        ReplayFailure reportedFailure = failure.get();
        if (reportedFailure != null) throw reportedFailure;
    }
}
