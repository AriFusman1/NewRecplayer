package com.pcapplayer.playback;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

/** A clock that answers {@code start}, then {@code start + tick}, {@code start + 2·tick}, ... one step per read. */
final class TickingClock extends Clock {

    private final Instant start;
    private final Duration tick;
    private final AtomicInteger reads = new AtomicInteger();

    TickingClock(Instant start, Duration tick) {
        this.start = start;
        this.tick = tick;
    }

    /** The instant the n-th read (from 0) returned. */
    Instant read(int n) {
        return start.plus(tick.multipliedBy(n));
    }

    int reads() {
        return reads.get();
    }

    @Override
    public Instant instant() {
        return read(reads.getAndIncrement());
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
