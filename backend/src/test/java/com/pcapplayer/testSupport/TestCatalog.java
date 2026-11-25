package com.pcapplayer.testSupport;

import com.pcapplayer.icd.loading.IcdRegistry;
import com.pcapplayer.icd.model.IcdDefinition;
import com.pcapplayer.pcapProcessing.extract.CachingExtractor;
import com.pcapplayer.pcapProcessing.extract.ExtractionSettings;
import com.pcapplayer.pcapProcessing.extract.MessageExtractor;
import com.pcapplayer.pcapProcessing.summary.CaptureScanner;
import com.pcapplayer.persistence.FileCache;
import com.pcapplayer.persistence.filesystem.FileSystemBlobStore;
import com.pcapplayer.recordManagement.repository.CatalogRepository;
import com.pcapplayer.recordManagement.service.CatalogService;
import com.pcapplayer.uploadHandling.UploadService;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/** A real catalog on a temporary folder, as the server builds it. */
public final class TestCatalog {

    public static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-24T10:00:00Z"), ZoneOffset.UTC);

    public final FileSystemBlobStore store;
    public final CatalogRepository repo;
    public final UploadService uploads;
    public final CatalogService catalog;

    /** @param builtIns the Java ICDs the server has */
    public TestCatalog(Path dir, IcdDefinition... builtIns) throws IOException {
        store = new FileSystemBlobStore(dir.resolve("store"));
        repo = new CatalogRepository(store, 20);
        repo.load();
        ExtractionSettings settings = ExtractionSettings.defaults();
        IcdRegistry registry = new IcdRegistry();
        for (IcdDefinition icd : builtIns) registry.put(icd);
        uploads = new UploadService(dir.resolve("uploads"), new CaptureScanner(settings), 60_000);
        catalog = new CatalogService(repo, uploads,
                new FileCache(store, dir.resolve("cache"), 1 << 20),
                new CachingExtractor(new MessageExtractor(settings)::extract, 1 << 20),
                registry, 100, CLOCK, () -> { });
    }
}
