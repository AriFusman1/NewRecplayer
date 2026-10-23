package com.pcapplayer.replay;

/** Live counters of the item being played; written by the replay thread, read by snapshots. */
public final class ItemProgress {

    private volatile long zeroNanos;       // System.nanoTime() of replay time zero; 0 = not started
    private volatile long frozenPosition = -1; // position while waiting for a reconnect
    private volatile long sentMessages;
    private volatile long sentBytes;
    private final long durationNanos;
    private final long totalMessages;

    public ItemProgress(long durationNanos, long totalMessages) {
        this.durationNanos = durationNanos;
        this.totalMessages = totalMessages;
    }

    void start(long zero) {
        zeroNanos = zero;
        frozenPosition = -1;
    }

    void freeze(long position) {
        frozenPosition = position;
    }

    void sent(int bytes) {
        sentMessages++;
        sentBytes += bytes;
    }

    /** Nanoseconds from range start that playback has reached. */
    public long positionNanos() {
        if (frozenPosition >= 0) return frozenPosition;
        if (zeroNanos == 0) return 0;
        return Math.max(0, Math.min(durationNanos, System.nanoTime() - zeroNanos));
    }

    public long sentMessages() {
        return sentMessages;
    }

    public long sentBytes() {
        return sentBytes;
    }

    public long totalMessages() {
        return totalMessages;
    }

    public long durationNanos() {
        return durationNanos;
    }
}
