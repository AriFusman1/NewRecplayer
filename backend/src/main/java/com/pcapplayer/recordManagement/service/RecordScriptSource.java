package com.pcapplayer.recordManagement.service;

import com.pcapplayer.pcapProcessing.decode.Ipv4Packet;
import com.pcapplayer.pcapProcessing.extract.CachingExtractor;
import com.pcapplayer.pcapProcessing.extract.ExtractionFilter;
import com.pcapplayer.pcapProcessing.extract.ExtractionResult;
import com.pcapplayer.pcapProcessing.script.Histogram;
import com.pcapplayer.persistence.FileCache;
import com.pcapplayer.playback.ReplayFailure;
import com.pcapplayer.playback.model.ScriptSource;
import com.pcapplayer.playback.model.TimeFix;
import com.pcapplayer.recordManagement.model.Category;
import com.pcapplayer.recordManagement.model.PcapFile;
import com.pcapplayer.recordManagement.model.Record;
import com.pcapplayer.recordManagement.repository.CatalogRepository;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Turns stored records into replay scripts for the engine, with their time-fix plans when the
 * category has an ICD and a profile. Errors are {@link ReplayFailure}s.
 */
public class RecordScriptSource implements ScriptSource {

    private final CatalogRepository repo;
    private final FileCache cache;
    private final CachingExtractor extractor;
    private final int maxBins;
    private final TimeFixPlans timeFixPlans;

    RecordScriptSource(CatalogRepository repo, FileCache cache, CachingExtractor extractor, int maxBins, TimeFixPlans timeFixPlans) {
        this.repo = repo;
        this.cache = cache;
        this.extractor = extractor;
        this.maxBins = maxBins;
        this.timeFixPlans = timeFixPlans;
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
            ExtractionFilter filter = ExtractionFilter.ofMicros(info.mode(), record.ports(), ip, record.startUs(), record.endUs());
            ExtractionResult extraction = extractor.extract(file.sha256(), path, filter);
            Category category = repo.category(record.categoryId());
            TimeFix timeFix = timeFixPlans.planFor(category, file.sha256(), filter, extraction.script());
            return new Prepared(info, record.startUs(), extraction.script(), Histogram.of(extraction.script(), maxBins), timeFix);
        } catch (IOException e) {
            throw new ReplayFailure("PREPARE_FAILED", Map.of("reason", String.valueOf(e.getMessage())), "Cannot load pcap");
        }
    }
}
