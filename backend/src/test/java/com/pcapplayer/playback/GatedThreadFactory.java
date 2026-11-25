package com.pcapplayer.playback;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;

/** Threads that start but do not run their task until {@link #open()}: a session stopped "before its thread runs". */
final class GatedThreadFactory implements ThreadFactory {

    private final CountDownLatch gate = new CountDownLatch(1);

    @Override
    public Thread newThread(Runnable task) {
        return new Thread(() -> {
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            task.run();
        });
    }

    void open() {
        gate.countDown();
    }
}
