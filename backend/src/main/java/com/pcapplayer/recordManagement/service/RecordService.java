package com.pcapplayer.recordManagement.service;

import com.pcapplayer.common.Strings;
import com.pcapplayer.errorHandling.ApiException;
import com.pcapplayer.pcapProcessing.decode.Ipv4Packet;
import com.pcapplayer.pcapProcessing.diagnostics.Warning;
import com.pcapplayer.pcapProcessing.extract.CachingExtractor;
import com.pcapplayer.pcapProcessing.extract.ExtractionFilter;
import com.pcapplayer.pcapProcessing.extract.ExtractionResult;
import com.pcapplayer.pcapProcessing.script.EConnectionMode;
import com.pcapplayer.pcapProcessing.script.Histogram;
import com.pcapplayer.pcapProcessing.summary.CaptureSummary;
import com.pcapplayer.persistence.FileCache;
import com.pcapplayer.recordManagement.model.Category;
import com.pcapplayer.recordManagement.model.PcapFile;
import com.pcapplayer.recordManagement.model.Record;
import com.pcapplayer.recordManagement.model.RecordStats;
import com.pcapplayer.recordManagement.model.Tag;
import com.pcapplayer.recordManagement.repository.CatalogRepository;
import com.pcapplayer.uploadHandling.UploadService;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.pcapplayer.recordManagement.service.CatalogSupport.nameExists;
import static com.pcapplayer.recordManagement.service.CatalogSupport.nameTaken;
import static com.pcapplayer.recordManagement.service.CatalogSupport.requireName;
import static com.pcapplayer.recordManagement.service.CatalogSupport.storage;
import static com.pcapplayer.recordManagement.service.CatalogSupport.validationFailed;
import static com.pcapplayer.recordManagement.service.CatalogSupport.writeToStorage;

/**
 * Records: the live preview while one is being defined, creating (with duplicate handling),
 * editing and deleting them, and the pcap files they point at.
 */
public class RecordService {

    // ------------------------------------------------------------------ request / response types

    /** What the record would be built from: an upload in progress, or a stored file. */
    public record Draft(String uploadId, String fileId, String categoryId, List<Integer> ports, String ip,
                        long startUs, long endUs) {
    }

    /** @param resolution null = report duplicates (409); "NEW_NAME" = save, auto-numbering the name */
    public record CreateRequest(String uploadId, String fileId, String name, String description, String categoryId,
                                List<String> tagIds, List<Integer> ports, String ip, long startUs, long endUs,
                                String createdBy, String resolution) {

        Draft draft() {
            return new Draft(uploadId, fileId, categoryId, ports, ip, startUs, endUs);
        }
    }

    public record UpdateRequest(String name, String description, long startUs, long endUs) {
    }

    /** @param stats null when the draft is too invalid to compute anything */
    public record Preview(RecordStats stats, List<Warning> errors) {
    }

    /** An already stored copy of an uploaded file, and the records that use it. */
    public record SameFile(String fileId, String originalName, List<String> records) {
    }

    /** A capture to compute from: its content hash (cache key), what it contains, and a local copy. */
    private record Source(String sha256, CaptureSummary summary, Path path) {
    }

    /** @param otherProtocolOnly no messages, but the filter matches packets of the other protocol */
    private record Computed(RecordStats stats, boolean otherProtocolOnly) {
    }

    /** A time window in capture microseconds, both ends inclusive. */
    private record TimeRange(long startUs, long endUs) {
        long durationUs() {
            return endUs - startUs;
        }
    }

    private static final long US_PER_SECOND = TimeUnit.SECONDS.toMicros(1);

    private final CatalogSupport catalog;
    private final CatalogRepository repo;
    private final UploadService uploads;
    private final FileCache cache;
    private final CachingExtractor extractor;
    private final int maxBins;

    RecordService(CatalogSupport catalog, UploadService uploads, FileCache cache, CachingExtractor extractor, int maxBins) {
        this.catalog = catalog;
        this.repo = catalog.repo;
        this.uploads = uploads;
        this.cache = cache;
        this.extractor = extractor;
        this.maxBins = maxBins;
    }

    public CaptureSummary fileSummary(String fileId) {
        return catalog.requireFile(fileId).summary();
    }

    /** For downloads. */
    public PcapFile fileOfRecord(String recordId) {
        return catalog.requireFile(catalog.requireRecord(recordId).fileId());
    }

    /** The stored file with this content, if any (an upload of a file we already have). */
    public SameFile sameFile(String sha256) {
        PcapFile file = repo.fileBySha(sha256);
        return file == null ? null : new SameFile(file.id(), file.originalName(), recordNamesUsing(file.id()));
    }

    // ------------------------------------------------------------------ preview / validation

    public Preview preview(Draft draft) {
        Source source = sourceOf(draft);
        Category category = draft.categoryId() == null ? null : repo.category(draft.categoryId());
        List<Warning> errors = new ArrayList<>();
        if (category == null) errors.add(Warning.of("CATEGORY_REQUIRED"));
        if (draft.ports() == null || draft.ports().isEmpty()) errors.add(Warning.of("PORTS_REQUIRED"));
        Integer ip = parseOptionalIp(draft.ip(), errors);
        TimeRange range = checkRange(source.summary(), draft.startUs(), draft.endUs(), errors);
        if (!errors.isEmpty()) return new Preview(null, errors);

        Computed computed = compute(source, category.mode(), draft.ports(), ip, range);
        if (computed.stats().messageCount() == 0) errors.add(noMessagesWarning(computed, category.mode()));
        return new Preview(computed.stats(), errors);
    }

    /** The draft's IP filter: null when not given, or when invalid (reported in {@code errors}). */
    private static Integer parseOptionalIp(String ip, List<Warning> errors) {
        if (Strings.isBlank(ip)) return null;

        try {
            return Ipv4Packet.parseIp(ip);
        } catch (IllegalArgumentException e) {
            errors.add(Warning.of("INVALID_IP", "ip", ip));
            return null;
        }
    }

    /** Why the selection is empty: its packets are of the other protocol, or there are none. */
    private static Warning noMessagesWarning(Computed computed, EConnectionMode mode) {
        if (!computed.otherProtocolOnly()) return Warning.of("NO_MESSAGES");
        return Warning.of("PROTOCOL_MISMATCH", "expected", mode.protocol(), "found", mode.tcp() ? "UDP" : "TCP");
    }

    /**
     * Times are entered with one-second resolution, so a range that covers the recording's first and
     * last second is accepted and clamped to the exact recording bounds.
     */
    private static TimeRange checkRange(CaptureSummary summary, long startUs, long endUs, List<Warning> errors) {
        if (startUs > endUs) {
            errors.add(Warning.of("INVALID_TIME_RANGE"));
            return new TimeRange(startUs, endUs);
        }
        long minStart = Math.floorDiv(summary.startUs(), US_PER_SECOND) * US_PER_SECOND;
        long maxEnd = Math.floorDiv(summary.endUs(), US_PER_SECOND) * US_PER_SECOND + US_PER_SECOND - 1;

        if (startUs < minStart || endUs > maxEnd) {
            errors.add(Warning.of("TIME_OUTSIDE_RECORDING", "startUs", summary.startUs(), "endUs", summary.endUs()));
        }
        return new TimeRange(Math.max(startUs, summary.startUs()), Math.min(endUs, summary.endUs()));
    }

    private Computed compute(Source source, EConnectionMode mode, List<Integer> ports, Integer ip, TimeRange range) {
        ExtractionFilter filter = ExtractionFilter.ofMicros(mode, ports, ip, range.startUs(), range.endUs());
        ExtractionResult extraction;

        try {
            extraction = extractor.extract(source.sha256(), source.path(), filter);
        } catch (IOException e) {
            throw ApiException.unprocessable("INVALID_FILE", ApiException.reason(e), "Cannot read capture");
        }
        Histogram histogram = Histogram.of(extraction.script(), maxBins);
        RecordStats stats = new RecordStats(mode.protocol(), extraction.messageCount(), extraction.script().totalBytes(),
                range.durationUs(), extraction.connections(),
                TimeUnit.NANOSECONDS.toMicros(extraction.firstMessageNanos()),
                TimeUnit.NANOSECONDS.toMicros(extraction.lastMessageNanos()), extraction.warnings(),
                TimeUnit.NANOSECONDS.toMicros(histogram.binNanos()), histogram.counts());
        return new Computed(stats, extraction.messageCount() == 0 && extraction.otherProtocolPackets() > 0);
    }

    private Source sourceOf(Draft draft) {
        if (!Strings.isBlank(draft.uploadId())) {
            UploadService.Upload upload = uploads.get(draft.uploadId());
            return new Source(upload.sha256(), upload.summary(), upload.path());
        }
        if (Strings.isBlank(draft.fileId())) throw ApiException.badRequest("SOURCE_REQUIRED", "uploadId or fileId is required");
        return stored(catalog.requireFile(draft.fileId()));
    }

    private Source stored(PcapFile file) {
        return new Source(file.sha256(), file.summary(), localCopy(file));
    }

    private Path localCopy(PcapFile file) {
        try {
            return cache.get(file.objectKey());
        } catch (IOException e) {
            throw storage(e, "Cannot fetch pcap from storage");
        }
    }

    // ------------------------------------------------------------------ create / update / delete

    public Record create(CreateRequest request) {
        String name = requireName(request.name());
        Preview preview = preview(request.draft());
        if (!preview.errors().isEmpty()) throw validationFailed(preview.errors());
        Category category = repo.category(request.categoryId());
        List<String> tagIds = validTags(request.tagIds(), category.id());
        UploadService.Upload upload = Strings.isBlank(request.uploadId()) ? null : uploads.get(request.uploadId());

        PcapFile existing = repo.exclusive(() -> {
            PcapFile sameFile = upload != null ? repo.fileBySha(upload.sha256()) : null;
            if (request.resolution() == null) rejectDuplicates(name, sameFile);
            return upload == null ? catalog.requireFile(request.fileId()) : sameFile;
        });
        PcapFile file = existing != null ? existing : storeUpload(upload, request.createdBy());
        if (existing != null && upload != null) uploads.discard(upload.id()); // identical file already stored

        return catalog.mutate(() -> {
            TimeRange range = checkRange(file.summary(), request.startUs(), request.endUs(), new ArrayList<>());
            long now = System.currentTimeMillis();
            Record record = new Record(UUID.randomUUID().toString(), file.id(), uniqueName(name),
                    Strings.trimToEmpty(request.description()), category.id(), tagIds,
                    List.copyOf(new LinkedHashSet<>(request.ports())), Strings.blankToNull(request.ip()), range.startUs(), range.endUs(),
                    preview.stats(), request.createdBy(), now, now);
            put(record);
            return record;
        });
    }

    /** 409 DUPLICATE when the name is taken or the same file is already stored, so the user can choose. */
    private void rejectDuplicates(String name, PcapFile sameFile) {
        Record byName = findByName(name, null);
        if (byName == null && sameFile == null) return;
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("nameConflict", byName == null ? null : byName.name());
        params.put("sameFile", sameFile == null ? null : Map.of("fileId", sameFile.id(),
                "originalName", sameFile.originalName(), "records", recordNamesUsing(sameFile.id())));
        params.put("existingFileId", sameFile != null ? sameFile.id() : byName.fileId());
        throw ApiException.conflict("DUPLICATE", params, "A record with this name or file already exists");
    }

    /** A new capture: store it once, then keep the upload as the local cached copy. */
    private PcapFile storeUpload(UploadService.Upload upload, String uploadedBy) {
        PcapFile file = new PcapFile(UUID.randomUUID().toString(), upload.sha256(), upload.originalName(),
                upload.extension(), upload.size(), upload.summary().format(), uploadedBy, System.currentTimeMillis(),
                upload.summary());

        try {
            repo.putFile(file, upload.path());
            uploads.consume(upload.id());
            cache.adopt(file.objectKey(), upload.path());
        } catch (IOException e) {
            throw storage(e, "Cannot store pcap");
        }
        return file;
    }

    public Record update(String id, UpdateRequest request) {
        Record old = catalog.requireRecord(id);
        String name = requireName(request.name());
        PcapFile file = catalog.requireFile(old.fileId());
        Category category = catalog.requireCategory(old.categoryId());
        List<Warning> errors = new ArrayList<>();
        TimeRange range = checkRange(file.summary(), request.startUs(), request.endUs(), errors);
        if (!errors.isEmpty()) throw validationFailed(errors);
        Integer ip = old.ip() == null ? null : Ipv4Packet.parseIp(old.ip());
        RecordStats stats = compute(stored(file), category.mode(), old.ports(), ip, range).stats();
        if (stats.messageCount() == 0) throw ApiException.unprocessable("NO_MESSAGES", "No messages in the selected range");

        return catalog.mutate(() -> {
            if (findByName(name, id) != null) throw nameExists("NAME_EXISTS", name, "Name already used");
            Record current = catalog.requireRecord(id);
            Record record = new Record(id, current.fileId(), name, Strings.trimToEmpty(request.description()),
                    current.categoryId(), current.tagIds(), current.ports(), current.ip(), range.startUs(), range.endUs(), stats,
                    current.createdBy(), current.createdAtMs(), System.currentTimeMillis());
            put(record);
            return record;
        });
    }

    public void delete(String id) {
        catalog.mutate(() -> {
            catalog.requireRecord(id);
            deleteRecordAndOrphanFile(id);
        });
    }

    // ------------------------------------------------------------------ used by TaxonomyService (under the catalog lock)

    int countInCategory(String categoryId) {
        return (int) repo.records().stream().filter(record -> record.categoryId().equals(categoryId)).count();
    }

    void deleteAllInCategory(String categoryId) {
        for (Record record : repo.records()) {
            if (record.categoryId().equals(categoryId)) deleteRecordAndOrphanFile(record.id());
        }
    }

    void removeTagEverywhere(String tagId) {
        for (Record record : repo.records()) {
            if (record.tagIds().contains(tagId)) {
                List<String> tags = new ArrayList<>(record.tagIds());
                tags.remove(tagId);
                put(record.withTags(tags));
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Deletes the record, and its pcap file too when no other record uses it. */
    private void deleteRecordAndOrphanFile(String id) {
        Record record = repo.record(id);
        if (record == null) return;

        writeToStorage(() -> {
            repo.deleteRecord(id);
            boolean stillUsed = repo.records().stream().anyMatch(other -> other.fileId().equals(record.fileId()));

            if (!stillUsed) {
                PcapFile file = repo.file(record.fileId());
                repo.deleteFile(record.fileId());
                if (file != null) cache.forget(file.objectKey());
            }
        });
    }

    private List<String> validTags(List<String> tagIds, String categoryId) {
        if (tagIds == null) return List.of();
        Map<String, Tag> byId = new LinkedHashMap<>();
        for (Tag tag : repo.tags()) byId.put(tag.id(), tag);
        List<String> out = new ArrayList<>();

        for (String id : new LinkedHashSet<>(tagIds)) {
            Tag tag = byId.get(id);
            if (tag == null) continue;

            if (tag.categoryId() != null && !tag.categoryId().equals(categoryId)) {
                throw ApiException.unprocessable("TAG_NOT_IN_CATEGORY", Map.of("tag", tag.name()), "Tag belongs to another category");
            }
            out.add(id);
        }
        return out;
    }

    private Record findByName(String name, String exceptId) {
        for (Record record : repo.records()) {
            if (record.name().equalsIgnoreCase(name) && !record.id().equals(exceptId)) return record;
        }
        return null;
    }

    /** Like a file explorer: "name", then "name (1)", "name (2)", … */
    private String uniqueName(String name) {
        List<Record> all = repo.records();
        if (!nameTaken(all, Record::name, Record::id, name, null)) return name;

        for (int i = 1; ; i++) {
            String candidate = name + " (" + i + ")";
            if (!nameTaken(all, Record::name, Record::id, candidate, null)) return candidate;
        }
    }

    private List<String> recordNamesUsing(String fileId) {
        return repo.records().stream().filter(record -> record.fileId().equals(fileId)).map(Record::name).toList();
    }

    private void put(Record record) {
        writeToStorage(() -> repo.putRecord(record));
    }
}
