package com.pcapplayer.icd.timefix;

import com.pcapplayer.icd.decode.RateLimitedLog;
import com.pcapplayer.icd.model.EFieldType;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/** Counts of planning and patching for one record; problems are logged, rate-limited. Thread-safe. */
public final class TimeFixStats {

    private static final RateLimitedLog LOG = new RateLimitedLog(Logger.getLogger(TimeFixStats.class.getName()), 10, TimeUnit.SECONDS);

    private final AtomicLong planned = new AtomicLong();
    private final AtomicLong skippedMessages = new AtomicLong();
    private final AtomicLong skippedFields = new AtomicLong();
    private final AtomicLong patched = new AtomicLong();
    private final AtomicLong clamped = new AtomicLong();

    void planned() {
        planned.incrementAndGet();
    }

    void skippedMessage(String message, String reason) {
        skippedMessages.incrementAndGet();
        LOG.warn("skip-message", () -> "Time fix: message " + message + " sent unchanged: " + reason);
    }

    void skippedField(String message, String path, String reason) {
        skippedFields.incrementAndGet();
        LOG.warn("skip-field", () -> "Time fix: " + message + "." + path + " not fixed: " + reason);
    }

    void patched() {
        patched.incrementAndGet();
    }

    void clamped(String message, String path, EFieldType type) {
        clamped.incrementAndGet();
        LOG.warn("clamp", () -> "Time fix: the new value of " + message + "." + path + " does not fit " + type + "; clamped");
    }

    public long plannedMessages() {
        return planned.get();
    }

    public long skippedMessages() {
        return skippedMessages.get();
    }

    public long skippedFields() {
        return skippedFields.get();
    }

    public long patchedMessages() {
        return patched.get();
    }

    public long clampedValues() {
        return clamped.get();
    }
}
