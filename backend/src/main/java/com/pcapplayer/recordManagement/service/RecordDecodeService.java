package com.pcapplayer.recordManagement.service;

import com.pcapplayer.common.Strings;
import com.pcapplayer.errorHandling.ApiException;
import com.pcapplayer.icd.analysis.CollectingAnalyzer;
import com.pcapplayer.icd.analysis.DecodeReport;
import com.pcapplayer.icd.analysis.MessageCountAnalyzer;
import com.pcapplayer.icd.decode.DecodeStats;
import com.pcapplayer.icd.decode.ScriptDecoder;
import com.pcapplayer.icd.loading.IcdRegistry;
import com.pcapplayer.icd.loading.JsonIcdLoader;
import com.pcapplayer.icd.model.IcdDefinition;
import com.pcapplayer.pcapProcessing.decode.Ipv4Packet;
import com.pcapplayer.pcapProcessing.extract.CachingExtractor;
import com.pcapplayer.pcapProcessing.extract.ExtractionFilter;
import com.pcapplayer.pcapProcessing.script.ReplayScript;
import com.pcapplayer.persistence.FileCache;
import com.pcapplayer.recordManagement.model.Category;
import com.pcapplayer.recordManagement.model.PcapFile;
import com.pcapplayer.recordManagement.model.Record;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.pcapplayer.recordManagement.service.CatalogSupport.storage;

/** Decodes a record's messages with an ICD, for checking an ICD against real traffic (debug endpoint). */
public class RecordDecodeService {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 10_000;

    private final CatalogSupport catalog;
    private final FileCache cache;
    private final CachingExtractor extractor;
    private final IcdRegistry icds;

    RecordDecodeService(CatalogSupport catalog, FileCache cache, CachingExtractor extractor, IcdRegistry icds) {
        this.catalog = catalog;
        this.cache = cache;
        this.extractor = extractor;
        this.icds = icds;
    }

    /**
     * @param limit  how many messages to return (default 100); counts and stats always cover the whole record
     * @param opcode return only this opcode (decimal or 0x hex); null for all
     * @param icdId  decode with this ICD instead of the category's (to try an ICD before mapping it)
     */
    public DecodeReport decode(String recordId, Integer limit, String opcode, String icdId) {
        Record record = catalog.requireRecord(recordId);
        Category category = catalog.requireCategory(record.categoryId());
        IcdDefinition icd = icdFor(category, icdId);
        Long opcodeFilter = parseOpcode(opcode);
        int messageLimit = limit == null ? DEFAULT_LIMIT : Math.max(0, Math.min(limit, MAX_LIMIT));

        ReplayScript script = scriptOf(record, category);
        MessageCountAnalyzer counts = new MessageCountAnalyzer();
        CollectingAnalyzer collected = new CollectingAnalyzer(messageLimit, opcodeFilter);
        DecodeStats stats = ScriptDecoder.decode(script, TimeUnit.MICROSECONDS.toNanos(record.startUs()), icd, counts, collected);
        return DecodeReport.of(icd.id(), script.steps().size(), stats, counts, collected);
    }

    private IcdDefinition icdFor(Category category, String requestedId) {
        String id = Strings.isBlank(requestedId) ? category.icdId() : requestedId.trim();
        if (id == null) throw ApiException.unprocessable("NO_ICD", Map.of("category", category.name()), "The category has no ICD");

        IcdDefinition icd = icds.find(id);
        if (icd == null) throw ApiException.notFound("ICD_NOT_FOUND", Map.of("id", id), "ICD not found");
        return icd;
    }

    private static Long parseOpcode(String opcode) {
        if (Strings.isBlank(opcode)) return null;

        Long value = JsonIcdLoader.parseNumber(opcode);
        if (value == null) throw ApiException.badRequest("INVALID_OPCODE", "opcode must be a number (decimal or 0x hex)");
        return value;
    }

    /** The record's script, exactly as playback gets it (and from the same cache). */
    private ReplayScript scriptOf(Record record, Category category) {
        PcapFile file = catalog.requireFile(record.fileId());

        try {
            Path path = cache.get(file.objectKey());
            Integer ip = record.ip() == null ? null : Ipv4Packet.parseIp(record.ip());
            ExtractionFilter filter = ExtractionFilter.ofMicros(category.mode(), record.ports(), ip, record.startUs(), record.endUs());
            return extractor.extract(file.sha256(), path, filter).script();
        } catch (IOException e) {
            throw storage(e, "Cannot read the record's pcap");
        }
    }
}
