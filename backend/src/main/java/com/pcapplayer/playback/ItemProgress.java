package com.pcapplayer.playback;

import java.util.concurrent.atomic.AtomicLong;

/** Live counters of the item being played; written by the replay thread, read by snapshots. */
public class ItemProgress {

    private final AtomicLong zeroNanos = new AtomicLong();          // System.nanoTime() of replay time zero; 0 = not started
    private final AtomicLong frozenPosition = new AtomicLong(-1);  // position while waiting for a reconnect
    private final AtomicLong sentMessages = new AtomicLong();
    private final AtomicLong sentBytes = new AtomicLong();
    /** Messages whose time came while a keep-trying TCP transport had no connection. */
    private final AtomicLong missedMessages = new AtomicLong();
    private final long durationNanos;
    private final long totalMessages;

    public ItemProgress(long durationNanos, long totalMessages) {
        this.durationNanos = durationNanos;
        this.totalMessages = totalMessages;
    }

    void start(long zero) {
        zeroNanos.set(zero);
        frozenPosition.set(-1);
    }

    void freeze(long position) {
        frozenPosition.set(position);
    }

    /** Playback jumped: time zero moved and the counters now reflect what lies before the new position. */
    void seek(long zero, long messagesBefore, long bytesBefore) {
        sentMessages.set(messagesBefore);
        sentBytes.set(bytesBefore);
        missedMessages.set(0);
        start(zero);
    }

    void sent(int bytes) {
        sentMessages.incrementAndGet();
        sentBytes.addAndGet(bytes);
    }

    void missed() {
        missedMessages.incrementAndGet();
    }

    /** Nanoseconds from range start that playback has reached. */
    public long positionNanos() {
        long frozen = frozenPosition.get();
        if (frozen >= 0) return frozen;
        long zero = zeroNanos.get();
        if (zero == 0) return 0;
        return Math.max(0, Math.min(durationNanos, System.nanoTime() - zero));
    }

    public long sentMessages() {
        return sentMessages.get();
    }

    public long sentBytes() {
        return sentBytes.get();
    }

    public long missedMessages() {
        return missedMessages.get();
    }

    public long totalMessages() {
        return totalMessages;
    }

    public long durationNanos() {
        return durationNanos;
    }
}
