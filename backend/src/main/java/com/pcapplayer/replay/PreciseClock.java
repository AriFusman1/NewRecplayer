package com.pcapplayer.replay;

import java.util.concurrent.locks.LockSupport;

/**
 * Waits until an absolute {@link System#nanoTime()} deadline: parks while far away (in slices, so
 * Stop is noticed), then spin-waits the last stretch for sub-millisecond precision.
 * <p>
 * How late the OS wakes a parked thread varies a lot (tens of µs on a typical Linux server,
 * several ms on a laptop with timer coalescing), so the spin margin adapts: it tracks the worst
 * recently observed oversleep and keeps a safety factor above it, decaying slowly when the
 * system is quiet. Spinning therefore costs only as much CPU as this host needs.
 */
public final class PreciseClock {

    private static final long MIN_MARGIN = 200_000;         // 0.2 ms
    private static final long MAX_MARGIN = 10_000_000;      // 10 ms
    private static final long MAX_PARK_NANOS = 20_000_000;  // re-check Stop at least every 20 ms

    /** Current spin margin, shared by all replay threads (they run on the same host). */
    private static volatile long margin = 1_500_000;

    private PreciseClock() {
    }

    public static void sleepUntil(long deadline, Control control) {
        while (true) {
            control.checkAlive();
            long now = System.nanoTime();
            long remaining = deadline - now;
            if (remaining <= 0) return;
            long m = margin;
            if (remaining > m) {
                long park = Math.min(remaining - m, MAX_PARK_NANOS);
                LockSupport.parkNanos(park);
                learn(System.nanoTime() - (now + park));
            } else {
                while (System.nanoTime() < deadline) Thread.onSpinWait();
                control.checkAlive();
                return;
            }
        }
    }

    /** @param oversleep how much later than requested the park returned */
    private static void learn(long oversleep) {
        if (Thread.currentThread().isInterrupted()) return; // woken early on purpose
        long m = margin;
        long wanted = oversleep * 2 + MIN_MARGIN;
        long next = wanted > m ? wanted : m - (m - wanted) / 64; // grow at once, shrink slowly
        margin = Math.max(MIN_MARGIN, Math.min(MAX_MARGIN, next));
    }

    static long marginNanos() {
        return margin;
    }
}
