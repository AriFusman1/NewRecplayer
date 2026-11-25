package com.pcapplayer.autotag.job;

import com.pcapplayer.autotag.archive.ArchiveObject;
import com.pcapplayer.autotag.archive.ArchiveService;
import com.pcapplayer.autotag.archive.TimeSpan;
import com.pcapplayer.autotag.rules.TagRule;
import com.pcapplayer.common.Strings;
import com.pcapplayer.errorHandling.ApiException;
import com.pcapplayer.icd.model.IcdDefinition;
import com.pcapplayer.icd.tree.IcdTreeBuilder;
import com.pcapplayer.pcapProcessing.extract.ExtractionSettings;
import com.pcapplayer.recordManagement.model.Category;
import com.pcapplayer.recordManagement.repository.CatalogRepository;
import com.pcapplayer.recordManagement.service.CatalogService;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Auto-tagging jobs: creating them (with the batch limit), receiving local uploads, the queue,
 * cancelling, and the list of past jobs.
 * <p>
 * <b>Queue.</b> At most {@code maxConcurrentJobs} jobs run at once (a fixed pool, so the others wait
 * in FIFO order as PENDING). <b>Temp files.</b> Each job has a folder under {@code workDir/tagjobs/}:
 * uploaded files and the workers' decompressed copies. It is deleted when the job ends, fails or is
 * cancelled, and the whole tree is cleared at start-up. <b>Restarts.</b> A job that was not finished
 * when the server stopped is marked FAILED ("interrupted") at start-up; jobs are not resumed.
 */
public class TagJobService {

    private static final Logger LOG = Logger.getLogger(TagJobService.class.getName());
    static final String INTERRUPTED = "interrupted";
    static final String UPLOAD_ABANDONED = "upload abandoned";

    /**
     * @param uploadExpireMs a job still UPLOADING after this long is failed and its files deleted
     */
    public record Settings(int maxFilesPerRun, int workers, int maxConcurrentJobs, double floatTolerance, Path workDir,
                           long uploadExpireMs) {
    }

    /**
     * A new job.
     *
     * @param files  for local sources: the files the browser will upload, in order
     * @param keys   for ARCHIVE_FILES: the chosen keys
     * @param folder for folder sources: the folder (archive prefix, or the local folder's name for display)
     * @param fromUs time span start (epoch µs, inclusive); with toUs, for folder sources
     */
    public record CreateRequest(String categoryId, List<String> ruleIds, boolean evaluateOnly, ESourceKind kind,
                                List<JobInput> files, List<String> keys, String folder, boolean recursive, Long fromUs, Long toUs,
                                String createdBy) {
    }

    private final CatalogRepository repo;
    private final CatalogService catalog;
    private final ArchiveService archive;
    private final Settings settings;
    private final Clock clock;
    private final Path jobsDir;
    private final TagJobRunner runner;
    private final ExecutorService queue;
    private final Map<String, LiveJob> live = new ConcurrentHashMap<>();

    public TagJobService(CatalogRepository repo, CatalogService catalog, ArchiveService archive, ExtractionSettings extraction,
                         Settings settings, Clock clock) {
        this.repo = repo;
        this.catalog = catalog;
        this.archive = archive;
        this.settings = settings;
        this.clock = clock;
        this.jobsDir = settings.workDir().resolve("tagjobs");
        FileTagger tagger = new FileTagger(extraction, catalog.records(), catalog.taxonomy(), new Object(), clock);
        this.runner = new TagJobRunner(tagger, settings.workers(), settings.floatTolerance(), clock);
        this.queue = Executors.newFixedThreadPool(settings.maxConcurrentJobs(), task -> {
            Thread thread = new Thread(task, "tag-job");
            thread.setDaemon(true);
            return thread;
        });
    }

    public int maxFilesPerRun() {
        return settings.maxFilesPerRun();
    }

    // ------------------------------------------------------------------ start-up

    /** After the catalog is loaded: unfinished jobs from before a restart become FAILED, and old temp files go. */
    public void recoverInterrupted() {
        deleteTree(jobsDir);

        for (TagJob job : repo.tagJobs()) {
            if (job.status().finished() || live.containsKey(job.id())) continue;
            save(job.withStatus(ETagJobStatus.FAILED, INTERRUPTED, job.startedAtMs(), clock.millis()));
        }
    }

    // ------------------------------------------------------------------ create / upload / start / cancel

    public TagJob create(CreateRequest request) {
        if (request == null || request.kind() == null) throw ApiException.badRequest("TAGJOB_SOURCE_REQUIRED", "Choose where the files come from");
        Category category = request.categoryId() == null ? null : repo.category(request.categoryId());
        if (category == null) throw ApiException.notFound("Category");
        IcdDefinition icd = icdOf(category);
        List<TagRule> rules = catalog.tagRules().rulesForRun(category.id(), request.ruleIds(), IcdTreeBuilder.of(icd));
        List<JobInput> inputs = inputsOf(request);
        if (inputs.isEmpty()) throw ApiException.unprocessable("TAGJOB_NO_FILES", "The selection contains no files");

        if (inputs.size() > settings.maxFilesPerRun()) {
            throw ApiException.unprocessable("TAGJOB_TOO_MANY_FILES", Map.of("count", inputs.size(), "max", settings.maxFilesPerRun()),
                    "Too many files for one run");
        }
        TimeSpan span = span(request);
        TagJobSource source = new TagJobSource(request.kind(), Strings.blankToNull(request.folder()), request.recursive(),
                span == null ? null : span.startUs(), span == null ? null : span.endUs());
        long bytes = inputs.stream().mapToLong(JobInput::size).sum();
        ETagJobStatus status = request.kind().local() ? ETagJobStatus.UPLOADING : ETagJobStatus.PENDING;
        TagJob job = new TagJob(UUID.randomUUID().toString(), category.id(), category.name(), icd.id(), source, rules,
                request.evaluateOnly(), Strings.trimToEmpty(request.createdBy()), status, null, 0, bytes, 0, clock.millis(), 0, 0,
                inputs, List.of());
        LiveJob liveJob = new LiveJob(job, jobsDir.resolve(job.id()));

        try {
            Files.createDirectories(liveJob.dir.resolve("in"));
        } catch (IOException e) {
            throw ApiException.internal("TAGJOB_STORAGE", ApiException.reason(e), "Cannot create the job's temp folder");
        }
        live.put(job.id(), liveJob);
        save(job);
        if (!request.kind().local()) enqueue(liveJob);
        return job;
    }

    /** Where the upload of file {@code index} goes. The job must be UPLOADING. */
    public Path uploadTarget(String jobId, int index) {
        LiveJob liveJob = requireLive(jobId);
        TagJob job = liveJob.snapshot();
        if (job.status() != ETagJobStatus.UPLOADING) throw ApiException.conflict("TAGJOB_NOT_UPLOADING", Map.of(), "The job is not receiving files");
        if (index < 0 || index >= job.inputs().size()) throw ApiException.badRequest("TAGJOB_BAD_INDEX", "No such file in the job");
        return uploadedPath(liveJob, index);
    }

    public void fileReceived(String jobId, int index) {
        requireLive(jobId).markUploaded(index);
    }

    /** Local uploads are complete: the job joins the queue. Files that never arrived are FAILED. */
    public TagJob start(String jobId) {
        LiveJob liveJob = requireLive(jobId);
        TagJob job = liveJob.update(current -> {
            if (current.status() != ETagJobStatus.UPLOADING) {
                throw ApiException.conflict("TAGJOB_NOT_UPLOADING", Map.of(), "The job is not receiving files");
            }
            return current.withStatus(ETagJobStatus.PENDING, null, 0, 0);
        });
        save(job);
        enqueue(liveJob);
        return job;
    }

    /** Stops the job; finished jobs are left as they are. */
    public TagJob cancel(String jobId) {
        LiveJob liveJob = live.get(jobId);
        if (liveJob == null) return require(jobId);
        liveJob.cancel();
        TagJob job = liveJob.snapshot();

        if (job.status() == ETagJobStatus.PENDING || job.status() == ETagJobStatus.UPLOADING) {
            // not running: nothing will finish it, so do it here
            deleteTree(liveJob.dir);
            finish(liveJob, new TagJobRunner.Outcome(ETagJobStatus.CANCELLED, null));
            live.remove(jobId);
        }
        return liveJob.snapshot();
    }

    /** Fails jobs whose upload stopped long ago (the browser was closed). */
    public void purgeAbandoned() {
        long now = clock.millis();

        for (LiveJob liveJob : List.copyOf(live.values())) {
            TagJob job = liveJob.snapshot();
            if (job.status() != ETagJobStatus.UPLOADING || now - job.createdAtMs() < settings.uploadExpireMs()) continue;
            liveJob.cancel();
            deleteTree(liveJob.dir);
            finish(liveJob, new TagJobRunner.Outcome(ETagJobStatus.FAILED, UPLOAD_ABANDONED));
            live.remove(job.id());
        }
    }

    // ------------------------------------------------------------------ reads

    /** What auto-tagging can do here, for the UI. */
    public record Capabilities(boolean archiveConfigured, int maxFilesPerRun) {
    }

    public Capabilities capabilities() {
        return new Capabilities(archive.configured(), settings.maxFilesPerRun());
    }

    /** How many archive files (and bytes) a selection holds, before the job is created. */
    public ArchiveService.Preview previewArchive(CreateRequest request) {
        if (request == null || request.kind() == null || request.kind().local()) {
            throw ApiException.badRequest("TAGJOB_SOURCE_REQUIRED", "Choose archive files or an archive folder");
        }
        List<ArchiveObject> objects = request.kind() == ESourceKind.ARCHIVE_FILES
                ? archive.selectFiles(request.keys())
                : archive.selectFolder(request.folder(), request.recursive(), span(request));
        return archive.preview(objects);
    }

    /** Every job, newest first, without per-file details. */
    public List<TagJobSummary> list() {
        List<TagJob> jobs = new ArrayList<>(repo.tagJobs());
        jobs.replaceAll(job -> live.containsKey(job.id()) ? live.get(job.id()).snapshot() : job);
        jobs.sort(Comparator.comparingLong(TagJob::createdAtMs).reversed());
        return jobs.stream().map(TagJobSummary::of).toList();
    }

    /** @param status only results with this status; null for all */
    public TagJob get(String jobId, EFileStatus status) {
        TagJob job = require(jobId);
        if (status == null) return job;
        return job.withResults(job.filesDone(), job.bytesDone(), job.results().stream().filter(result -> result.status() == status).toList());
    }

    // ------------------------------------------------------------------ helpers

    private void enqueue(LiveJob liveJob) {
        TagJob job = liveJob.snapshot();
        Category category = repo.category(job.categoryId());
        IcdDefinition icd = catalog.icds().find(job.icdId());
        JobFileSource source = job.source().kind().local()
                ? (index, input) -> Files.newInputStream(uploadedPath(liveJob, index))
                : (index, input) -> archive.store().open(input.path());

        queue.submit(() -> {
            TagJobRunner.Outcome outcome;

            try {
                outcome = category == null || icd == null
                        ? new TagJobRunner.Outcome(ETagJobStatus.FAILED, "the category or its ICD was deleted")
                        : runner.run(liveJob, category.mode(), icd, missingAsFailures(liveJob, source), this::save);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Tagging job " + job.id() + " failed", e);
                outcome = new TagJobRunner.Outcome(ETagJobStatus.FAILED, String.valueOf(e.getMessage()));
            }
            // temp files first, then the final status: a finished job never has files left on disk
            deleteTree(liveJob.dir);
            if (outcome != null) finish(liveJob, outcome);
            live.remove(job.id());
        });
    }

    /** A local file the browser never uploaded is reported as a FAILED file, not as an unreadable job. */
    private static JobFileSource missingAsFailures(LiveJob liveJob, JobFileSource source) {
        if (!liveJob.snapshot().source().kind().local()) return source;

        return (index, input) -> {
            if (!liveJob.isUploaded(index)) throw new IOException("the file was not uploaded");
            return source.open(index, input);
        };
    }

    private List<JobInput> inputsOf(CreateRequest request) {
        return switch (request.kind()) {
            case LOCAL_FILES, LOCAL_FOLDER -> {
                List<JobInput> files = request.files() == null ? List.of() : request.files();
                yield files.stream().filter(file -> file != null && file.size() > 0 && !Strings.isBlank(file.name()))
                        .map(file -> new JobInput(file.name(), Strings.isBlank(file.path()) ? file.name() : file.path(), file.size()))
                        .toList();
            }
            case ARCHIVE_FILES -> toInputs(archive.selectFiles(request.keys()));
            case ARCHIVE_FOLDER -> toInputs(archive.selectFolder(request.folder(), request.recursive(), span(request)));
        };
    }

    private static List<JobInput> toInputs(List<ArchiveObject> objects) {
        return objects.stream().map(object -> new JobInput(object.fileName(), object.key(), object.size())).toList();
    }

    private static TimeSpan span(CreateRequest request) {
        if (request.fromUs() == null && request.toUs() == null) return null;
        long from = request.fromUs() == null ? Long.MIN_VALUE : request.fromUs();
        long to = request.toUs() == null ? Long.MAX_VALUE : request.toUs();
        if (from > to) throw ApiException.unprocessable("INVALID_TIME_RANGE", "The span starts after it ends");
        return new TimeSpan(from, to);
    }

    private IcdDefinition icdOf(Category category) {
        IcdDefinition icd = catalog.icds().find(category.icdId());
        if (icd == null) throw ApiException.unprocessable("NO_ICD", Map.of("category", category.name()), "The category has no ICD");
        return icd;
    }

    private static Path uploadedPath(LiveJob liveJob, int index) {
        return liveJob.dir.resolve("in").resolve(index + ".upload");
    }

    private LiveJob requireLive(String jobId) {
        LiveJob liveJob = live.get(jobId);
        if (liveJob == null) throw ApiException.notFound("TAGJOB_NOT_FOUND", Map.of("id", String.valueOf(jobId)), "No such running job");
        return liveJob;
    }

    private TagJob require(String jobId) {
        LiveJob liveJob = live.get(jobId);
        if (liveJob != null) return liveJob.snapshot();
        TagJob job = repo.tagJob(jobId);
        if (job == null) throw ApiException.notFound("TAGJOB_NOT_FOUND", Map.of("id", String.valueOf(jobId)), "No such job");
        return job;
    }

    /** Storage failures while saving progress are logged; the job goes on (the final save retries). */
    private void save(TagJob job) {
        try {
            repo.putTagJob(job);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Cannot store tagging job " + job.id(), e);
        }
    }

    private void finish(LiveJob liveJob, TagJobRunner.Outcome outcome) {
        save(liveJob.update(current -> current.withStatus(outcome.status(), outcome.reason(), current.startedAtMs(), clock.millis())));
    }

    static void deleteTree(Path dir) {
        if (!Files.exists(dir)) return;

        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException | UncheckedIOException e) {
            LOG.log(Level.WARNING, "Cannot delete " + dir, e);
        }
    }
}
