package com.pcapplayer.catalog.service;

import com.pcapplayer.catalog.model.Category;
import com.pcapplayer.catalog.model.PcapFile;
import com.pcapplayer.catalog.model.Record;
import com.pcapplayer.catalog.repository.CatalogRepository;
import com.pcapplayer.pcap.decode.Ipv4Packet;
import com.pcapplayer.pcap.extract.CachingExtractor;
import com.pcapplayer.pcap.extract.ExtractionFilter;
import com.pcapplayer.pcap.extract.ExtractionResult;
import com.pcapplayer.pcap.script.Histogram;
import com.pcapplayer.replay.ReplayFailure;
import com.pcapplayer.replay.model.ScriptSource;
import com.pcapplayer.storage.FileCache;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** Turns stored records into replay scripts for the engine. Errors are {@link ReplayFailure}s. */
public class RecordScriptSource implements ScriptSource {

    private final CatalogRepository repo;
    private final FileCache cache;
    private final CachingExtractor extractor;
    private final int maxBins;

    RecordScriptSource(CatalogRepository repo, FileCache cache, CachingExtractor extractor, int maxBins) {
        this.repo = repo;
        this.cache = cache;
        this.extractor = extractor;
        this.maxBins = maxBins;
    }

    @Override
    public RecordInfo describe(String recordId) {
        Record record = repo.record(recordId);
        if (record == null) throw new ReplayFailure("RECORD_NOT_FOUND", Map.of("recordId", recordId), "Record not found");
        Category category = repo.category(record.categoryId());
        if (category == null) throw new ReplayFailure("RECORD_NOT_FOUND", Map.of("recordId", recordId), "Category not found");
        return new RecordInfo(record.id(), record.name(), category.name(), category.mode());
    }

    @Override
    public Prepared prepare(String recordId) {
        RecordInfo info = describe(recordId);
        Record record = repo.record(recordId);
        PcapFile file = repo.file(record.fileId());
        if (file == null) throw new ReplayFailure("PREPARE_FAILED", Map.of("reason", "file missing"), "pcap file missing");
        try {
            Path path = cache.get(file.objectKey());
            Integer ip = record.ip() == null ? null : Ipv4Packet.parseIp(record.ip());
            ExtractionResult extraction = extractor.extract(file.sha256(), path,
                    ExtractionFilter.ofMicros(info.mode(), record.ports(), ip, record.startUs(), record.endUs()));
            return new Prepared(info, record.startUs(), extraction.script(), Histogram.of(extraction.script(), maxBins));
        } catch (IOException e) {
            throw new ReplayFailure("PREPARE_FAILED", Map.of("reason", String.valueOf(e.getMessage())), "Cannot load pcap");
        }
    }
}
