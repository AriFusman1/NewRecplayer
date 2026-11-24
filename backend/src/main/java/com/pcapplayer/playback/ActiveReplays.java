package com.pcapplayer.playback;

import java.util.Map;

/**
 * The global limit on replays running at the same time, for all users together. A replay holds
 * one slot from the moment it is started (including while it waits for a connection) until it
 * ends, whatever the reason.
 * <p>
 * Callers are HTTP worker threads (several at once) and the replay threads themselves, so both
 * methods are {@code synchronized}: checking for room and taking it is one atomic step, and two
 * users pressing Play at the same moment can never get past the limit together. The app runs as
 * one process with one engine, so a plain Java lock is all that is needed.
 */
final class ActiveReplays {

    private final int max;
    private int active;

    ActiveReplays(int max) {
        this.max = max;
    }

    /**
     * Takes {@code count} slots, or none at all.
     *
     * @throws ReplayFailure ACTIVE_LIMIT with {@code free}, {@code requested} and {@code max}
     */
    synchronized void take(int count) {
        int free = max - active;
        if (count > free) {
            throw new ReplayFailure("ACTIVE_LIMIT", Map.of("free", free, "requested", count, "max", max),
                    "There are " + free + " free slots and you tried to start " + count);
        }
        active += count;
    }

    synchronized void release(int count) {
        active = Math.max(0, active - count);
    }

    synchronized int active() {
        return active;
    }

    int max() {
        return max;
    }
}
