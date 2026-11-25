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
import com.pcapplayer.recordManagement.model.TaggingStats;
import com.pcapplayer.recordManagement.repository.CatalogRepository;
import com.pcapplayer.uploadHandling.UploadService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.pcapplayer.recordManagement.service.CatalogSupport.nameExists;
import static com.pcapplayer.recordManagement.service.CatalogSupport.nameTaken;
import static com.pcapplayer.recordManagement.service.CatalogSupport.requireName;
import static com.pcapplayer.recordManagement.service.CatalogSupport.requireSameMode;
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

    /**
     * @param categoryId null = keep; otherwise a category with the record's connection mode
     * @param tagIds     null = keep; unknown ids are dropped
     */
    /** A capture auto-tagging matched, ready to be saved (see {@link #saveTagged}). */
    public record TaggedCapture(Path pcap, String name, long size, String sha256, CaptureSummary summary, String categoryId,
                                List<Integer> ports, long startUs, long endUs, String description, List<String> tagIds,
                                TaggingStats tagging, String createdBy) {
    }

    /** @param updatedExisting true when existing records got the stats instead of a new record */
    public record TaggedSave(boolean updatedExisting, List<String> recordIds) {
    }

    public record UpdateRequest(String name, String description, long startUs, long endUs, String categoryId,
                                List<String> tagIds) {
    }

    /** Tags to add to and remove from several records at once; unknown ids are ignored. */
    public record RetagRequest(List<String> recordIds, List<String> add, List<String> remove) {
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
        return create(request, null);
    }

    /** @param tagging stats from auto-tagging, or null for a record made by hand */
    private Record create(CreateRequest request, TaggingStats tagging) {
        String name = requireName(request.name());
        Preview preview = preview(request.draft());
        if (!preview.errors().isEmpty()) throw validationFailed(preview.errors());
        Category category = repo.category(request.categoryId());
        List<String> tagIds = validTags(request.tagIds());
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
                    preview.stats(), request.createdBy(), now, now, tagging);
            put(record);
            return record;
        });
    }

    // ------------------------------------------------------------------ auto-tagging

    /**
     * Saves a capture that auto-tagging matched.
     * <ul>
     *   <li>When records of this category already use a stored file with the same SHA-256 (of the
     *       decompressed content), no record is created: those records get the new statistics and tags.</li>
     *   <li>Otherwise a record is created through {@link #create} like a normal upload. If the same file is
     *       stored for another category, the record reuses it (the file is stored once).</li>
     * </ul>
     * The capture file is owned by this method afterwards: stored, or deleted.
     */
    public TaggedSave saveTagged(TaggedCapture capture) {
        List<Record> existing = repo.exclusive(() -> {
            PcapFile file = repo.fileBySha(capture.sha256());
            if (file == null) return List.<Record>of();
            return repo.records().stream()
                    .filter(record -> record.fileId().equals(file.id()) && record.categoryId().equals(capture.categoryId())).toList();
        });

        if (!existing.isEmpty()) {
            List<String> ids = catalog.mutate(() -> {
                List<String> updated = new ArrayList<>();

                for (Record old : existing) {
                    Record current = repo.record(old.id());
                    if (current == null) continue; // deleted meanwhile
                    Set<String> tags = new LinkedHashSet<>(current.tagIds());
                    tags.addAll(capture.tagIds());
                    put(current.withTagging(capture.tagging(), List.copyOf(tags)));
                    updated.add(current.id());
                }
                return updated;
            });
            deleteQuietly(capture.pcap());
            return new TaggedSave(true, ids);
        }
        UploadService.Upload upload = uploads.registerScanned(capture.pcap(), capture.name(), capture.size(), capture.sha256(),
                capture.summary(), capture.createdBy());

        try {
            CreateRequest request = new CreateRequest(upload.id(), null, recordName(capture.name()), capture.description(),
                    capture.categoryId(), capture.tagIds(), capture.ports(), null, capture.startUs(), capture.endUs(),
                    capture.createdBy(), "NEW_NAME");
            return new TaggedSave(false, List.of(create(request, capture.tagging()).id()));
        } finally {
            uploads.discard(upload.id()); // nothing to do once stored; deletes the file if saving failed
        }
    }

    /** "capture.pcap" → "capture". */
    private static String recordName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String name = dot > 0 ? fileName.substring(0, dot) : fileName;
        return Strings.isBlank(name) ? "capture" : name;
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // a temp file
        }
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

    /**
     * Name, description, times, category and tags. The new category must have the record's connection
     * mode, since the mode decides which ports and packets the record is built from.
     */
    public Record update(String id, UpdateRequest request) {
        Record old = catalog.requireRecord(id);
        String name = requireName(request.name());
        PcapFile file = catalog.requireFile(old.fileId());
        Category category = catalog.requireCategory(old.categoryId());
        String categoryId = request.categoryId() == null ? category.id() : request.categoryId();
        requireSameMode(category, catalog.requireCategory(categoryId));
        List<Warning> errors = new ArrayList<>();
        TimeRange range = checkRange(file.summary(), request.startUs(), request.endUs(), errors);
        if (!errors.isEmpty()) throw validationFailed(errors);
        Integer ip = old.ip() == null ? null : Ipv4Packet.parseIp(old.ip());
        RecordStats stats = compute(stored(file), category.mode(), old.ports(), ip, range).stats();
        if (stats.messageCount() == 0) throw ApiException.unprocessable("NO_MESSAGES", "No messages in the selected range");

        return catalog.mutate(() -> {
            if (findByName(name, id) != null) throw nameExists("NAME_EXISTS", name, "Name already used");
            Record current = catalog.requireRecord(id);
            catalog.requireCategory(categoryId); // may have been deleted meanwhile
            List<String> tagIds = request.tagIds() == null ? current.tagIds() : validTags(request.tagIds());
            Record record = new Record(id, current.fileId(), name, Strings.trimToEmpty(request.description()),
                    categoryId, tagIds, current.ports(), current.ip(), range.startUs(), range.endUs(), stats,
                    current.createdBy(), current.createdAtMs(), System.currentTimeMillis(), current.tagging());
            put(record);
            return record;
        });
    }

    /**
     * Adds and removes tags on several records in one catalog write. Records deleted meanwhile are
     * skipped; a tag in both lists ends up removed.
     *
     * @return how many records changed
     */
    public int retag(RetagRequest request) {
        List<String> recordIds = request.recordIds() == null ? List.of() : request.recordIds();
        if (recordIds.isEmpty()) throw ApiException.unprocessable("RECORDS_REQUIRED", "Choose at least one record");

        return catalog.mutate(() -> {
            List<String> add = validTags(request.add());
            Set<String> remove = request.remove() == null ? Set.of()
                    : request.remove().stream().filter(Objects::nonNull).collect(Collectors.toSet());
            int changed = 0;

            for (String recordId : new LinkedHashSet<>(recordIds)) {
                Record record = recordId == null ? null : repo.record(recordId);
                if (record == null) continue;
                LinkedHashSet<String> tags = new LinkedHashSet<>(record.tagIds());
                tags.addAll(add);
                tags.removeAll(remove);
                List<String> newTags = List.copyOf(tags);

                if (!newTags.equals(record.tagIds())) {
                    put(record.withTags(newTags));
                    changed++;
                }
            }
            return changed;
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

    void moveAllInCategory(String fromCategoryId, String toCategoryId) {
        for (Record record : repo.records()) {
            if (record.categoryId().equals(fromCategoryId)) put(record.withCategory(toCategoryId));
        }
    }

    /** Puts {@code intoTagId} wherever {@code tagId} was (once), and removes {@code tagId}. */
    void replaceTagEverywhere(String tagId, String intoTagId) {
        for (Record record : repo.records()) {
            if (record.tagIds().contains(tagId)) {
                LinkedHashSet<String> tags = new LinkedHashSet<>();
                for (String id : record.tagIds()) tags.add(id.equals(tagId) ? intoTagId : id);
                put(record.withTags(List.copyOf(tags)));
            }
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

    /** Keeps the known tag ids, without duplicates. Any tag can be used in any category. */
    private List<String> validTags(List<String> tagIds) {
        if (tagIds == null) return List.of();
        Set<String> known = repo.tags().stream().map(Tag::id).collect(Collectors.toSet());
        return new LinkedHashSet<>(tagIds).stream().filter(known::contains).toList();
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
