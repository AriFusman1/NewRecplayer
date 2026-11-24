package com.pcapplayer.recordManagement.service;

import com.pcapplayer.icd.loading.IcdRegistry;
import com.pcapplayer.icd.model.IcdDefinition;
import com.pcapplayer.pcapProcessing.extract.CachingExtractor;
import com.pcapplayer.pcapProcessing.extract.ExtractionSettings;
import com.pcapplayer.pcapProcessing.extract.MessageExtractor;
import com.pcapplayer.pcapProcessing.summary.CaptureScanner;
import com.pcapplayer.persistence.FileCache;
import com.pcapplayer.persistence.filesystem.FileSystemBlobStore;
import com.pcapplayer.recordManagement.repository.CatalogRepository;
import com.pcapplayer.uploadHandling.UploadService;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/** A real catalog on a temporary folder, as the server builds it. */
final class TestCatalog {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-24T10:00:00Z"), ZoneOffset.UTC);

    final FileSystemBlobStore store;
    final CatalogRepository repo;
    final CatalogService catalog;

    /** @param builtIns the Java ICDs the server has */
    TestCatalog(Path dir, IcdDefinition... builtIns) throws IOException {
        store = new FileSystemBlobStore(dir.resolve("store"));
        repo = new CatalogRepository(store, 20);
        repo.load();
        ExtractionSettings settings = ExtractionSettings.defaults();
        IcdRegistry registry = new IcdRegistry();
        for (IcdDefinition icd : builtIns) registry.put(icd);
        catalog = new CatalogService(repo,
                new UploadService(dir.resolve("uploads"), new CaptureScanner(settings), 60_000),
                new FileCache(store, dir.resolve("cache"), 1 << 20),
                new CachingExtractor(new MessageExtractor(settings)::extract, 1 << 20),
                registry, 100, CLOCK, () -> { });
    }
}
