package com.pcapplayer.autotag.job;

import com.pcapplayer.autotag.rules.CompiledRules;
import com.pcapplayer.icd.model.IcdDefinition;
import com.pcapplayer.icd.tree.IcdTreeBuilder;
import com.pcapplayer.pcapProcessing.script.EConnectionMode;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs one job: its files go through a bounded pool of workers ({@code workers} at a time, each with
 * at most one decompressed file). A failing file is a FAILED result; the run goes on.
 * <p>
 * Cancelling sets a flag that workers check between files and every 1024 decoded messages, and
 * interrupts in-flight reads; files in flight are abandoned, results so far are kept. The job's temp
 * folder is always deleted at the end.
 */
final class TagJobRunner {

    private static final Logger LOG = Logger.getLogger(TagJobRunner.class.getName());

    private final FileTagger tagger;
    private final int workers;
    private final double floatTolerance;
    private final Clock clock;

    TagJobRunner(FileTagger tagger, int workers, double floatTolerance, Clock clock) {
        this.tagger = tagger;
        this.workers = workers;
        this.floatTolerance = floatTolerance;
        this.clock = clock;
    }

    /**
     * @param save writes the job to storage (status changes always, progress at most every 2 s)
     */
    void run(LiveJob live, EConnectionMode mode, IcdDefinition icd, JobFileSource source, Consumer<TagJob> save) {
        if (live.cancelled.get()) return; // cancelled while waiting in the queue: already marked CANCELLED
        save.accept(live.update(job -> job.withStatus(ETagJobStatus.RUNNING, null, clock.millis(), 0)));
        ExecutorService pool = Executors.newFixedThreadPool(workers, task -> {
            Thread thread = new Thread(task, "tag-worker");
            thread.setDaemon(true);
            return thread;
        });
        live.workers(pool);

        try {
            TagJob job = live.snapshot();
            CompiledRules rules = CompiledRules.compile(job.rules(), IcdTreeBuilder.of(icd), floatTolerance);
            FileTagger.Context context = new FileTagger.Context(job, mode, icd, rules, source, live.dir, live);
            List<Future<?>> pending = new ArrayList<>();

            for (int i = 0; i < job.inputs().size(); i++) {
                int index = i;
                JobInput input = job.inputs().get(i);
                pending.add(pool.submit(() -> processOne(live, context, index, input, save)));
            }
            awaitAll(pending);
            finish(live, live.cancelled.get() ? ETagJobStatus.CANCELLED : ETagJobStatus.DONE, null, save);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Tagging job " + live.snapshot().id() + " failed", e);
            finish(live, ETagJobStatus.FAILED, String.valueOf(e.getMessage()), save);
        } finally {
            pool.shutdownNow();
        }
    }

    private void processOne(LiveJob live, FileTagger.Context context, int index, JobInput input, Consumer<TagJob> save) {
        if (live.cancelled.get()) return;
        FileResult result = tagger.process(context, index, input);
        if (result == null) return; // abandoned because of a cancel
        TagJob job = live.addResult(result, input.size());
        if (live.saveDue(clock.millis())) save.accept(job);
    }

    private static void awaitAll(List<Future<?>> pending) {
        for (Future<?> future : pending) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException | CancellationException e) {
                // a cancelled or failed task: its file has no result (FileTagger handles its own errors)
            }
        }
    }

    private void finish(LiveJob live, ETagJobStatus status, String reason, Consumer<TagJob> save) {
        save.accept(live.update(job -> job.withStatus(status, reason, job.startedAtMs(), clock.millis())));
    }
}
