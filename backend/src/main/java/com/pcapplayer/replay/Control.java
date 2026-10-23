package com.pcapplayer.replay;

/**
 * Shared between a replay thread, its transport's helper threads and whoever presses Stop.
 * The replay thread calls {@link #checkAlive()} while it waits so stops and connection failures
 * surface promptly.
 */
public final class Control {

    private volatile String stoppedBy;
    private volatile ReplayFailure failure;
    private volatile Thread owner;
    private volatile AutoCloseable resource;

    void bind(Thread t) {
        owner = t;
    }

    /** Closed on stop, so a replay thread blocked in a socket call wakes up. */
    public void closeOnAbort(AutoCloseable c) {
        resource = c;
        if (stoppedBy != null) abort();
    }

    public void stop(String by) {
        if (stoppedBy == null) stoppedBy = by == null ? "" : by;
        abort();
    }

    /** Reported by a transport helper thread (e.g. the peer disconnected). First failure wins. */
    public void fail(ReplayFailure f) {
        if (failure == null) failure = f;
        Thread t = owner;
        if (t != null) t.interrupt();
    }

    private void abort() {
        Thread t = owner;
        if (t != null) t.interrupt();
        AutoCloseable c = resource;
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
                // closing only to unblock
            }
        }
    }

    public boolean stopped() {
        return stoppedBy != null;
    }

    public String stoppedBy() {
        return stoppedBy;
    }

    public void checkAlive() {
        if (stoppedBy != null) throw new StoppedException();
        ReplayFailure f = failure;
        if (f != null) throw f;
    }
}
