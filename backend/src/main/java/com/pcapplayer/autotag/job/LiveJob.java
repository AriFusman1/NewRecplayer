package com.pcapplayer.autotag.job;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

/**
 * A job that is not finished yet: its current state, shared by the workers. The {@link TagJob}
 * record is immutable, so every change replaces it under this object's lock.
 */
final class LiveJob {

    /** Progress is written to storage at most this often while running. */
    private static final long SAVE_EVERY_MS = 2000;

    final Path dir;
    final AtomicBoolean cancelled = new AtomicBoolean();
    private TagJob job;
    private final boolean[] uploaded;
    private long savedAtMs;
    private volatile ExecutorService workers;

    LiveJob(TagJob job, Path dir) {
        this.job = job;
        this.dir = dir;
        this.uploaded = new boolean[job.inputs().size()];
    }

    synchronized TagJob snapshot() {
        return job;
    }

    synchronized TagJob update(UnaryOperator<TagJob> change) {
        job = change.apply(job);
        return job;
    }

    /** Adds a file's result and its bytes to the progress. */
    synchronized TagJob addResult(FileResult result, long bytes) {
        List<FileResult> results = new ArrayList<>(job.results());
        results.add(result);
        job = job.withResults(job.filesDone() + 1, job.bytesDone() + bytes, List.copyOf(results));
        return job;
    }

    /** True (and remembers the time) if it is time to write progress to storage. */
    synchronized boolean saveDue(long nowMs) {
        if (nowMs - savedAtMs < SAVE_EVERY_MS) return false;
        savedAtMs = nowMs;
        return true;
    }

    synchronized void markUploaded(int index) {
        uploaded[index] = true;
    }

    synchronized boolean isUploaded(int index) {
        return uploaded[index];
    }

    void workers(ExecutorService pool) {
        workers = pool;
    }

    /**
     * Sets the flag and interrupts in-flight reads; in-flight files are abandoned. Files still waiting
     * for a worker are dropped: {@code shutdownNow} returns them without completing their futures, so
     * they are cancelled here, or the runner would wait for them forever.
     */
    void cancel() {
        cancelled.set(true);
        ExecutorService pool = workers;
        if (pool == null) return;

        for (Runnable waiting : pool.shutdownNow()) {
            if (waiting instanceof Future<?> future) future.cancel(false);
        }
    }
}
