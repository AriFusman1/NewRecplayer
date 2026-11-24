package com.pcapplayer.playback;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Replays started together ("Play together"): one single-item session per record, sharing a start
 * gate. Each member prepares its script and then waits at the gate, so all of them begin playing
 * at the same moment even when one capture takes longer to load. A member that ends before
 * reaching the gate (failure or stop) still opens it for the others.
 */
final class ReplayGroup {

    /** Most records that can be played together. */
    static final int MAX_SIZE = 10;

    private static final long POLL_MS = 20;

    final String id = UUID.randomUUID().toString();
    final int size;
    private final CountDownLatch gate;

    ReplayGroup(int size) {
        this.size = size;
        this.gate = new CountDownLatch(size);
    }

    /** Called by a member once it is ready to play; returns when every member is ready (or gone). */
    void arriveAndAwait(Control control) {
        gate.countDown();

        try {
            while (!gate.await(POLL_MS, TimeUnit.MILLISECONDS)) {
                control.checkAlive(); // Stop still works while waiting for the others
            }
        } catch (InterruptedException e) {
            control.checkAlive();
            Thread.currentThread().interrupt();
        }
    }

    /** Called by a member that ended without arriving, so the others don't wait for it. */
    void leave() {
        gate.countDown();
    }
}
