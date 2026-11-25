package com.pcapplayer.playback;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * The global limit on replays running at the same time, for all users together. A replay holds
 * one slot from the moment it is started (including while it waits for a connection) until it
 * ends, whatever the reason.
 * <p>
 * Callers are HTTP worker threads (several at once) and the replay threads themselves, so every
 * method is {@code synchronized}: checking for room and taking it is one atomic step, and two
 * users pressing Play at the same moment can never get past the limit together. The app runs as
 * one process with one engine, so a plain Java lock is all that is needed.
 */
final class ActiveReplays {

    private static final Logger LOG = Logger.getLogger(ActiveReplays.class.getName());

    private final int max;
    private int active;
    /** Set on server shutdown: nothing new may start. */
    private boolean closed;

    ActiveReplays(int max) {
        this.max = max;
    }

    /**
     * Takes {@code count} slots, or none at all.
     *
     * @throws ReplayFailure ACTIVE_LIMIT with {@code free}, {@code requested} and {@code max};
     *                       SHUTTING_DOWN after {@link #close()}
     */
    synchronized void take(int count) {
        if (closed) throw new ReplayFailure("SHUTTING_DOWN", Map.of(), "The server is shutting down");
        int free = max - active;

        if (count > free) {
            throw new ReplayFailure("ACTIVE_LIMIT", Map.of("free", free, "requested", count, "max", max),
                    "There are " + free + " free slots and you tried to start " + count);
        }
        active += count;
    }

    /**
     * Gives back {@code count} slots. Giving back more than are taken is a bug (a slot released
     * twice): the counter stays at 0 so the limit keeps working, and a warning is logged.
     */
    synchronized void release(int count) {
        if (count > active) {
            LOG.warning("Released " + count + " replay slot(s) but only " + active + " were taken: a slot was released twice");
        }
        active = Math.max(0, active - count);
        if (active == 0) notifyAll(); // awaitIdle
    }

    /** From now on {@link #take} refuses; slots already taken are given back as usual. */
    synchronized void close() {
        closed = true;
    }

    synchronized int active() {
        return active;
    }

    /**
     * Waits until no slot is taken.
     *
     * @return true if none is taken, false if some still were after {@code ms}
     */
    synchronized boolean awaitIdle(long ms) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms);

        while (active > 0) {
            long leftMs = TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime());
            if (leftMs <= 0) return false;
            wait(leftMs);
        }
        return true;
    }

    int max() {
        return max;
    }
}
