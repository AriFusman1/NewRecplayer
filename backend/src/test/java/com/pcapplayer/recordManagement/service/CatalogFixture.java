package com.pcapplayer.recordManagement.service;

import com.pcapplayer.icd.loading.IcdRegistry;
import com.pcapplayer.pcapProcessing.extract.CachingExtractor;
import com.pcapplayer.pcapProcessing.extract.ExtractionSettings;
import com.pcapplayer.pcapProcessing.script.EConnectionMode;
import com.pcapplayer.pcapProcessing.summary.CaptureScanner;
import com.pcapplayer.pcapProcessing.summary.CaptureSummary;
import com.pcapplayer.persistence.FileCache;
import com.pcapplayer.persistence.filesystem.FileSystemBlobStore;
import com.pcapplayer.recordManagement.model.Category;
import com.pcapplayer.recordManagement.model.PcapFile;
import com.pcapplayer.recordManagement.model.Record;
import com.pcapplayer.recordManagement.repository.CatalogRepository;
import com.pcapplayer.uploadHandling.UploadService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * A catalog on a temp directory, with a stored "capture" whose extraction always yields messages
 * (see {@link FixedScriptParser}), so records can be created and edited without libpcap.
 */
final class CatalogFixture {

    static final long START_US = 1_700_000_000_000_000L;
    static final long END_US = START_US + 10_000_000L;

    final CatalogRepository repo;
    final CatalogService catalog;
    final PcapFile file;

    CatalogFixture(Path dir) throws IOException {
        FileSystemBlobStore store = new FileSystemBlobStore(dir.resolve("store"));
        repo = new CatalogRepository(store, 10);
        repo.load();
        FileCache cache = new FileCache(store, dir.resolve("cache"), 1_000_000);
        UploadService uploads = new UploadService(dir.resolve("uploads"),
                new CaptureScanner(ExtractionSettings.defaults()), 60_000);
        // no ICDs: these tests are about categories, tags and records, not time fixing
        catalog = new CatalogService(repo, uploads, cache, new CachingExtractor(new FixedScriptParser(3), 0), new IcdRegistry(), 10,
                Clock.systemUTC(), () -> {
                });
        file = storeFile(dir);
    }

    private PcapFile storeFile(Path dir) throws IOException {
        Path content = Files.write(dir.resolve("capture.pcap"), new byte[]{1, 2, 3});
        CaptureSummary summary = new CaptureSummary("pcap", 10, START_US, END_US, 0, 10, List.of(), List.of(), 0, new long[0]);
        PcapFile stored = new PcapFile(UUID.randomUUID().toString(), "sha", "capture.pcap", "pcap", 3, "pcap", "test", 0, summary);
        repo.putFile(stored, content);
        return stored;
    }

    Category category(String name, EConnectionMode mode) {
        return catalog.taxonomy().createCategory(name, mode);
    }

    Record record(String name, Category category, List<String> tagIds) {
        return catalog.records().create(new RecordService.CreateRequest(null, file.id(), name, "", category.id(), tagIds,
                List.of(5000), null, START_US, END_US, "test", "NEW_NAME"));
    }

    RecordService.UpdateRequest update(Record record, String categoryId, List<String> tagIds) {
        return new RecordService.UpdateRequest(record.name(), record.description(), record.startUs(), record.endUs(),
                categoryId, tagIds);
    }
}
