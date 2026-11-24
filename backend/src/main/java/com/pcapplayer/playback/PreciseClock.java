package com.pcapplayer.playback;

import java.util.concurrent.atomic.AtomicLong;
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
    private static final AtomicLong MARGIN = new AtomicLong(1_500_000);

    private PreciseClock() {
    }

    public static void sleepUntil(long deadline, Control control) {
        sleep(deadline, control, false);
    }

    /**
     * Like {@link #sleepUntil} but returns early when a seek is requested.
     *
     * @return true if the deadline was reached, false if a seek is pending
     */
    static boolean sleepUntilOrSeek(long deadline, Control control) {
        return sleep(deadline, control, true);
    }

    private static boolean sleep(long deadline, Control control, boolean seekable) {
        while (true) {
            control.checkAlive();
            if (seekable && control.seekPending()) return false;
            long now = System.nanoTime();
            long remaining = deadline - now;
            if (remaining <= 0) return true;
            long spinMargin = MARGIN.get();

            if (remaining > spinMargin) {
                long park = Math.min(remaining - spinMargin, MAX_PARK_NANOS);
                LockSupport.parkNanos(park);
                if (!(seekable && control.seekPending())) learn(System.nanoTime() - (now + park));
            } else {
                while (System.nanoTime() < deadline) {
                    if (seekable && control.seekPending()) return false;
                    Thread.onSpinWait();
                }
                control.checkAlive();
                return true;
            }
        }
    }

    /** @param oversleep how much later than requested the park returned */
    private static void learn(long oversleep) {
        if (Thread.currentThread().isInterrupted()) return; // woken early on purpose
        long wanted = oversleep * 2 + MIN_MARGIN;

        MARGIN.updateAndGet(spinMargin -> {
            long next = wanted > spinMargin ? wanted : spinMargin - (spinMargin - wanted) / 64; // grow at once, shrink slowly
            return Math.max(MIN_MARGIN, Math.min(MAX_MARGIN, next));
        });
    }

    static long marginNanos() {
        return MARGIN.get();
    }
}
