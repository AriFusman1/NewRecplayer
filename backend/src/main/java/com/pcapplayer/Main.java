package com.pcapplayer;

import com.pcapplayer.api.ApiServer;
import com.pcapplayer.api.LiveHub;
import com.pcapplayer.catalog.CatalogRepository;
import com.pcapplayer.catalog.CatalogService;
import com.pcapplayer.catalog.UploadService;
import com.pcapplayer.config.AppConfig;
import com.pcapplayer.config.ConfigLoader;
import com.pcapplayer.extract.CaptureScanner;
import com.pcapplayer.extract.ExtractionSettings;
import com.pcapplayer.extract.MessageExtractor;
import com.pcapplayer.replay.ReplayEngine;
import com.pcapplayer.storage.BlobStore;
import com.pcapplayer.storage.FileCache;
import com.pcapplayer.storage.FileSystemBlobStore;
import com.pcapplayer.storage.S3BlobStore;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Entry point: load config, build the services, start HTTP, load metadata from storage (retrying). */
public final class Main {

    private static final Logger LOG = Logger.getLogger(Main.class.getName());

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        AppConfig cfg = ConfigLoader.load(System.getenv());
        Path work = Path.of(cfg.workDir());

        BlobStore store = "filesystem".equalsIgnoreCase(cfg.storage().type())
                ? new FileSystemBlobStore(Path.of(cfg.storage().filesystemRoot()))
                : new S3BlobStore(new S3BlobStore.Settings(cfg.s3().endpoint(), cfg.s3().region(), cfg.s3().bucket(),
                cfg.s3().accessKey(), cfg.s3().secretKey(), cfg.s3().pathStyle(), cfg.s3().prefix()));

        ExtractionSettings ex = new ExtractionSettings(
                TimeUnit.MILLISECONDS.toNanos(cfg.extraction().fragmentTimeoutMs()),
                TimeUnit.MILLISECONDS.toNanos(cfg.extraction().tcpGapTimeoutMs()),
                TimeUnit.MILLISECONDS.toNanos(cfg.extraction().udpDuplicateWindowMs()));
        CatalogRepository repo = new CatalogRepository(store, cfg.history().size());
        FileCache cache = new FileCache(store, work.resolve("cache"), cfg.cache().maxBytes());
        UploadService uploads = new UploadService(work.resolve("uploads"), new CaptureScanner(ex),
                TimeUnit.MINUTES.toMillis(cfg.upload().expireMinutes()));

        LiveHub[] hub = new LiveHub[1];
        CatalogService catalog = new CatalogService(repo, uploads, cache, new MessageExtractor(ex),
                cfg.histogram().maxBins(), () -> hub[0].catalogChanged());
        hub[0] = new LiveHub(entry -> {
            try {
                catalog.addHistory(entry);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Could not store history entry", e);
            }
        });
        ReplayEngine engine = new ReplayEngine(catalog, hub[0], new ReplayEngine.Settings(cfg.replay().connectTimeoutMs()));

        Vertx vertx = Vertx.vertx(new VertxOptions()
                .setMaxWorkerExecuteTime(30).setMaxWorkerExecuteTimeUnit(TimeUnit.MINUTES));
        hub[0].start(vertx, engine);
        AtomicBoolean ready = new AtomicBoolean();
        ApiServer.start(vertx, cfg.server().host(), cfg.server().port(),
                        new ApiServer.Services(catalog, repo, uploads, cache, engine, hub[0], store, ready::get))
                .onSuccess(s -> LOG.info("PCAP Player listening on " + cfg.server().host() + ":" + s.actualPort()))
                .onFailure(e -> {
                    LOG.log(Level.SEVERE, "HTTP server failed to start", e);
                    System.exit(1);
                });

        vertx.setPeriodic(TimeUnit.MINUTES.toMillis(5), id -> vertx.executeBlocking(() -> {
            uploads.purgeExpired();
            return null;
        }, false));
        loadMetadata(vertx, repo, ready);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            engine.stopAll("server shutdown");
            try {
                Thread.sleep(1000); // let stopped items write their history entries
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            vertx.close();
        }));
    }

    /** Readiness stays DOWN until the metadata is loaded; retries every 5 s (storage may start later). */
    private static void loadMetadata(Vertx vertx, CatalogRepository repo, AtomicBoolean ready) {
        vertx.executeBlocking(() -> {
            repo.load();
            return null;
        }, false).onComplete(ar -> {
            if (ar.succeeded()) {
                ready.set(true);
            } else {
                LOG.warning("Loading metadata failed, retrying in 5 s: " + ar.cause().getMessage());
                vertx.setTimer(5000, id -> loadMetadata(vertx, repo, ready));
            }
        });
    }
}
