package com.pcapplayer.playback;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** Creates threads normally until the {@code failFrom}-th one (1-based), then fails like a JVM out of native threads. */
final class FailingThreadFactory implements ThreadFactory {

    private final int failFrom;
    private final AtomicInteger created = new AtomicInteger();

    FailingThreadFactory(int failFrom) {
        this.failFrom = failFrom;
    }

    @Override
    public Thread newThread(Runnable task) {
        if (created.incrementAndGet() >= failFrom) throw new OutOfMemoryError("unable to create native thread");
        return new Thread(task);
    }
}
