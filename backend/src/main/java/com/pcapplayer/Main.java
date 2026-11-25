package com.pcapplayer;

import com.pcapplayer.autotag.archive.ArchiveService;
import com.pcapplayer.autotag.archive.S3ArchiveStore;
import com.pcapplayer.autotag.job.TagJobService;
import com.pcapplayer.configuration.AppConfig;
import com.pcapplayer.configuration.ConfigLoader;
import com.pcapplayer.icd.loading.IcdRegistry;
import com.pcapplayer.pcapProcessing.extract.CachingExtractor;
import com.pcapplayer.pcapProcessing.extract.ExtractionSettings;
import com.pcapplayer.pcapProcessing.extract.MessageExtractor;
import com.pcapplayer.pcapProcessing.summary.CaptureScanner;
import com.pcapplayer.persistence.BlobStore;
import com.pcapplayer.persistence.FileCache;
import com.pcapplayer.persistence.filesystem.FileSystemBlobStore;
import com.pcapplayer.persistence.s3.S3BlobStore;
import com.pcapplayer.playback.ReplayEngine;
import com.pcapplayer.playback.model.HistoryEntry;
import com.pcapplayer.recordManagement.repository.CatalogRepository;
import com.pcapplayer.recordManagement.service.CatalogService;
import com.pcapplayer.uploadHandling.UploadService;
import com.pcapplayer.web.ApiServer;
import com.pcapplayer.web.live.LiveHub;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Entry point: load config, build the services, start HTTP, load metadata from storage (retrying). */
public final class Main {

    private static final Logger LOG = Logger.getLogger(Main.class.getName());

    /** Uploads and parsing run on worker threads and may legitimately take long. */
    private static final long MAX_WORKER_EXECUTE_MINUTES = 30;
    private static final long PURGE_UPLOADS_EVERY_MS = TimeUnit.MINUTES.toMillis(5);
    private static final long METADATA_RETRY_MS = TimeUnit.SECONDS.toMillis(5);

    /** On shutdown, time for stopped items to write their history entries. */
    private static final long SHUTDOWN_GRACE_MS = TimeUnit.SECONDS.toMillis(1);

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        AppConfig config = ConfigLoader.load(System.getenv());
        Path work = Path.of(config.workDir());

        BlobStore store = createBlobStore(config);
        ExtractionSettings extractionSettings = extractionSettings(config);
        CatalogRepository repo = new CatalogRepository(store, config.history().size());
        FileCache cache = new FileCache(store, work.resolve("cache"), config.cache().maxBytes());
        UploadService uploads = new UploadService(work.resolve("uploads"), new CaptureScanner(extractionSettings),
                TimeUnit.MINUTES.toMillis(config.upload().expireMinutes()));
        CachingExtractor extractor = new CachingExtractor(new MessageExtractor(extractionSettings)::extract,
                config.extraction().cacheMaxBytes());

        // The catalog notifies the hub of changes, and the hub stores history in the catalog.
        AtomicReference<LiveHub> hubRef = new AtomicReference<>();
        CatalogService catalog = new CatalogService(repo, uploads, cache, extractor, IcdRegistry.withBuiltIns(),
                config.histogram().maxBins(), Clock.systemUTC(), () -> hubRef.get().catalogChanged());
        LiveHub hub = new LiveHub(entry -> storeHistory(catalog, entry));
        hubRef.set(hub);
        ReplayEngine engine = new ReplayEngine(catalog.scriptSource(), hub,
                new ReplayEngine.Settings(config.replay().connectTimeoutMs(), config.replay().maxActiveReplays(),
                        config.replay().groupConnectTimeoutMs(), config.replay().tcpKeepTrying(),
                        config.replay().tcpRetryIntervalMs()));

        ArchiveService archive = new ArchiveService(config.archiveS3().configured() ? new S3ArchiveStore(config.archiveS3()) : null,
                config.autoTag().maxFilesPerRun());
        TagJobService tagJobs = new TagJobService(repo, catalog, archive, extractionSettings,
                new TagJobService.Settings(config.autoTag().maxFilesPerRun(), Math.max(1, config.autoTag().workers()),
                        Math.max(1, config.autoTag().maxConcurrentJobs()), config.autoTag().floatTolerance(), work,
                        TimeUnit.MINUTES.toMillis(config.upload().expireMinutes())),
                Clock.systemUTC());

        Vertx vertx = Vertx.vertx(new VertxOptions()
                .setMaxWorkerExecuteTime(MAX_WORKER_EXECUTE_MINUTES).setMaxWorkerExecuteTimeUnit(TimeUnit.MINUTES));
        hub.start(vertx, engine);
        AtomicBoolean ready = new AtomicBoolean();
        startHttp(vertx, config, new ApiServer.Services(catalog, uploads, cache, engine, hub, store, ready::get,
                tagJobs, archive));
        schedulePurge(vertx, uploads, tagJobs);
        loadMetadata(vertx, repo, ready, tagJobs);
        registerShutdownHook(vertx, engine);
    }

    private static BlobStore createBlobStore(AppConfig config) throws IOException {
        if ("filesystem".equalsIgnoreCase(config.storage().type())) {
            return new FileSystemBlobStore(Path.of(config.storage().filesystemRoot()));
        }
        AppConfig.S3 s3 = config.s3();
        return new S3BlobStore(new S3BlobStore.Settings(s3.endpoint(), s3.region(), s3.bucket(),
                s3.accessKey(), s3.secretKey(), s3.pathStyle(), s3.prefix()));
    }

    private static ExtractionSettings extractionSettings(AppConfig config) {
        return new ExtractionSettings(
                TimeUnit.MILLISECONDS.toNanos(config.extraction().fragmentTimeoutMs()),
                TimeUnit.MILLISECONDS.toNanos(config.extraction().tcpGapTimeoutMs()),
                TimeUnit.MILLISECONDS.toNanos(config.extraction().udpDuplicateWindowMs()));
    }

    /** History is best effort: a storage failure must not break the replay that produced the entry. */
    private static void storeHistory(CatalogService catalog, HistoryEntry entry) {
        try {
            catalog.addHistory(entry);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not store history entry", e);
        }
    }

    private static void startHttp(Vertx vertx, AppConfig config, ApiServer.Services services) {
        ApiServer.start(vertx, config.server().host(), config.server().port(), services)
                .onSuccess(server -> LOG.info("PCAP Player listening on " + config.server().host() + ":" + server.actualPort()))
                .onFailure(e -> {
                    LOG.log(Level.SEVERE, "HTTP server failed to start", e);
                    System.exit(1);
                });
    }

    private static void schedulePurge(Vertx vertx, UploadService uploads, TagJobService tagJobs) {
        vertx.setPeriodic(PURGE_UPLOADS_EVERY_MS, id -> vertx.executeBlocking(() -> {
            uploads.purgeExpired();
            tagJobs.purgeAbandoned();
            return null;
        }, false));
    }

    /** Readiness stays DOWN until the metadata is loaded; retries periodically (storage may start later). */
    private static void loadMetadata(Vertx vertx, CatalogRepository repo, AtomicBoolean ready, TagJobService tagJobs) {
        vertx.executeBlocking(() -> {
            repo.load();
            tagJobs.recoverInterrupted(); // jobs cut off by a restart become FAILED
            return null;
        }, false).onComplete(result -> {
            if (result.succeeded()) {
                ready.set(true);
            } else {
                LOG.warning("Loading metadata failed, retrying in " + METADATA_RETRY_MS + " ms: " + result.cause().getMessage());
                vertx.setTimer(METADATA_RETRY_MS, id -> loadMetadata(vertx, repo, ready, tagJobs));
            }
        });
    }

    private static void registerShutdownHook(Vertx vertx, ReplayEngine engine) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!engine.shutdown("server shutdown", SHUTDOWN_GRACE_MS)) {
                LOG.warning("Some replays had not ended " + SHUTDOWN_GRACE_MS + " ms after being stopped");
            }
            vertx.close();
        }));
    }
}
