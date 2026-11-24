package com.pcapplayer.icd.decode;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Logs at most one line per key per interval, so a broken stream cannot flood the log. Lines that were
 * held back are counted and the count is added to the next line for that key.
 */
public final class RateLimitedLog {

    private record KeyState(AtomicLong lastLogNanos, AtomicLong suppressed) {
    }

    private final Logger logger;
    private final long intervalNanos;
    private final Map<String, KeyState> keys = new ConcurrentHashMap<>();

    public RateLimitedLog(Logger logger, long interval, TimeUnit unit) {
        this.logger = logger;
        this.intervalNanos = unit.toNanos(interval);
    }

    public void warn(String key, Supplier<String> message) {
        KeyState state = keys.computeIfAbsent(key, newKey -> new KeyState(new AtomicLong(Long.MIN_VALUE), new AtomicLong()));
        long now = System.nanoTime();
        long last = state.lastLogNanos().get();
        boolean due = last == Long.MIN_VALUE || now - last >= intervalNanos;

        if (!due || !state.lastLogNanos().compareAndSet(last, now)) {
            state.suppressed().incrementAndGet();
            return;
        }
        long heldBack = state.suppressed().getAndSet(0);
        logger.log(Level.WARNING, () -> message.get() + (heldBack > 0 ? " (" + heldBack + " similar since the last report)" : ""));
    }
}
