package com.pcapplayer.catalog;

import com.pcapplayer.extract.CaptureSummary;
import com.pcapplayer.extract.ConnectionMode;
import com.pcapplayer.extract.ExtractionFilter;
import com.pcapplayer.extract.ExtractionResult;
import com.pcapplayer.extract.Histogram;
import com.pcapplayer.extract.MessageExtractor;
import com.pcapplayer.extract.Warning;
import com.pcapplayer.net.Ipv4Packet;
import com.pcapplayer.replay.HistoryEntry;
import com.pcapplayer.replay.ReplayFailure;
import com.pcapplayer.replay.ScriptSource;
import com.pcapplayer.storage.FileCache;
import com.pcapplayer.util.ApiException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Business rules for categories, tags, files and records; also resolves records into replay
 * scripts for the engine. Mutations are serialized on {@code this}; slow work (parsing, uploading
 * to S3) happens outside the lock.
 */
public final class CatalogService implements ScriptSource {

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

    public record FileView(String id, String sha256, String originalName, long size, String format,
                           String uploadedBy, long uploadedAtMs, long startUs, long endUs) {
    }

    public record Catalog(List<Category> categories, List<Tag> tags, List<Record> records, List<FileView> files) {
    }

    private record Source(CaptureSummary summary, Path path) {
    }

    /** @param otherProtocolOnly no messages, but the filter matches packets of the other protocol */
    private record Computed(RecordStats stats, boolean otherProtocolOnly) {
    }

    // ------------------------------------------------------------------

    private final CatalogRepository repo;
    private final UploadService uploads;
    private final FileCache cache;
    private final MessageExtractor extractor;
    private final int maxBins;
    private final Runnable onChange;

    public CatalogService(CatalogRepository repo, UploadService uploads, FileCache cache, MessageExtractor extractor,
                          int maxBins, Runnable onChange) {
        this.repo = repo;
        this.uploads = uploads;
        this.cache = cache;
        this.extractor = extractor;
        this.maxBins = maxBins;
        this.onChange = onChange;
    }

    public Catalog catalog() {
        List<FileView> files = repo.files().stream().map(f -> new FileView(f.id(), f.sha256(), f.originalName(),
                f.size(), f.format(), f.uploadedBy(), f.uploadedAtMs(), f.summary().startUs(), f.summary().endUs())).toList();
        return new Catalog(repo.categories(), repo.tags(), repo.records(), files);
    }

    public CaptureSummary fileSummary(String fileId) {
        return requireFile(fileId).summary();
    }

    public List<HistoryEntry> history() {
        return repo.history();
    }

    public void addHistory(HistoryEntry e) {
        try {
            repo.addHistory(e);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    // ------------------------------------------------------------------ preview / validation

    public Preview preview(Draft d) {
        Source src = source(d);
        Category cat = d.categoryId() == null ? null : repo.category(d.categoryId());
        List<Warning> errors = new ArrayList<>();
        if (cat == null) errors.add(Warning.of("CATEGORY_REQUIRED"));
        if (d.ports() == null || d.ports().isEmpty()) errors.add(Warning.of("PORTS_REQUIRED"));
        Integer ip = null;
        if (d.ip() != null && !d.ip().isBlank()) {
            try {
                ip = Ipv4Packet.parseIp(d.ip());
            } catch (IllegalArgumentException e) {
                errors.add(Warning.of("INVALID_IP", "ip", d.ip()));
            }
        }
        long[] range = checkRange(src.summary(), d.startUs(), d.endUs(), errors);
        if (!errors.isEmpty()) return new Preview(null, errors);

        Computed c = compute(src.path(), cat.mode(), d.ports(), ip, range[0], range[1]);
        if (c.stats().messageCount() == 0) {
            errors.add(c.otherProtocolOnly()
                    ? Warning.of("PROTOCOL_MISMATCH", "expected", cat.mode().protocol(),
                    "found", cat.mode().tcp() ? "UDP" : "TCP")
                    : Warning.of("NO_MESSAGES"));
        }
        return new Preview(c.stats(), errors);
    }

    /**
     * Times are entered with one-second resolution, so a range that covers the recording's first and
     * last second is accepted and clamped to the exact recording bounds.
     */
    private static long[] checkRange(CaptureSummary s, long startUs, long endUs, List<Warning> errors) {
        if (startUs > endUs) {
            errors.add(Warning.of("INVALID_TIME_RANGE"));
            return new long[]{startUs, endUs};
        }
        long minStart = Math.floorDiv(s.startUs(), 1_000_000L) * 1_000_000L;
        long maxEnd = Math.floorDiv(s.endUs(), 1_000_000L) * 1_000_000L + 999_999L;
        if (startUs < minStart || endUs > maxEnd) {
            errors.add(Warning.of("TIME_OUTSIDE_RECORDING", "startUs", s.startUs(), "endUs", s.endUs()));
        }
        return new long[]{Math.max(startUs, s.startUs()), Math.min(endUs, s.endUs())};
    }

    private Computed compute(Path file, ConnectionMode mode, List<Integer> ports, Integer ip, long startUs, long endUs) {
        ExtractionResult r = extract(file, mode, ports, ip, startUs, endUs);
        Histogram h = Histogram.of(r.script(), maxBins);
        RecordStats stats = new RecordStats(mode.protocol(), r.messageCount(), r.script().totalBytes(), endUs - startUs,
                r.connections(), r.firstMessageNanos() / 1000, r.lastMessageNanos() / 1000, r.warnings(),
                h.binNanos() / 1000, h.counts());
        return new Computed(stats, r.messageCount() == 0 && r.otherProtocolPackets() > 0);
    }

    private ExtractionResult extract(Path file, ConnectionMode mode, List<Integer> ports, Integer ip, long startUs, long endUs) {
        try {
            return extractor.extract(file, new ExtractionFilter(mode, new HashSet<>(ports), ip,
                    startUs * 1000, endUs * 1000 + 999));
        } catch (IOException e) {
            throw new ApiException(422, "INVALID_FILE", Map.of("reason", String.valueOf(e.getMessage())), "Cannot read capture");
        }
    }

    private Source source(Draft d) {
        if (d.uploadId() != null && !d.uploadId().isBlank()) {
            UploadService.Upload u = uploads.get(d.uploadId());
            return new Source(u.summary(), u.path());
        }
        if (d.fileId() == null || d.fileId().isBlank()) throw ApiException.bad("SOURCE_REQUIRED", "uploadId or fileId is required");
        PcapFile f = requireFile(d.fileId());
        return new Source(f.summary(), localCopy(f));
    }

    private Path localCopy(PcapFile f) {
        try {
            return cache.get(f.objectKey());
        } catch (IOException e) {
            throw new ApiException(503, "STORAGE_ERROR", Map.of("reason", String.valueOf(e.getMessage())), "Cannot fetch pcap from storage");
        }
    }

    // ------------------------------------------------------------------ records

    public Record createRecord(CreateRequest req) {
        String name = req.name() == null ? "" : req.name().trim();
        if (name.isEmpty()) throw new ApiException(422, "NAME_REQUIRED", "Name is required");
        Preview p = preview(req.draft());
        if (!p.errors().isEmpty()) {
            throw new ApiException(422, p.errors().get(0).code(), Map.of("errors", p.errors()), "Validation failed");
        }
        Category cat = repo.category(req.categoryId());
        List<String> tagIds = validTags(req.tagIds(), cat.id());
        UploadService.Upload upload = req.uploadId() != null && !req.uploadId().isBlank() ? uploads.get(req.uploadId()) : null;

        PcapFile file;
        synchronized (this) {
            Record byName = findByName(name, null);
            PcapFile sameFile = upload != null ? repo.fileBySha(upload.sha256()) : null;
            if (req.resolution() == null && (byName != null || sameFile != null)) {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("nameConflict", byName == null ? null : byName.name());
                params.put("sameFile", sameFile == null ? null : Map.of("fileId", sameFile.id(),
                        "originalName", sameFile.originalName(), "records", recordNamesUsing(sameFile.id())));
                params.put("existingFileId", sameFile != null ? sameFile.id() : byName.fileId());
                throw new ApiException(409, "DUPLICATE", params, "A record with this name or file already exists");
            }
            file = upload == null ? requireFile(req.fileId()) : sameFile;
        }

        if (file == null) {
            // New capture: store it once, then keep the upload as the local cached copy.
            file = new PcapFile(UUID.randomUUID().toString(), upload.sha256(), upload.originalName(), upload.extension(),
                    upload.size(), upload.summary().format(), req.createdBy(), System.currentTimeMillis(), upload.summary());
            try {
                repo.putFile(file, upload.path());
                uploads.consume(upload.id());
                cache.adopt(file.objectKey(), upload.path());
            } catch (IOException e) {
                throw new ApiException(503, "STORAGE_ERROR", Map.of("reason", String.valueOf(e.getMessage())), "Cannot store pcap");
            }
        } else if (upload != null) {
            uploads.discard(upload.id()); // identical file already stored
        }

        Record r;
        synchronized (this) {
            long[] range = checkRange(file.summary(), req.startUs(), req.endUs(), new ArrayList<>());
            long now = System.currentTimeMillis();
            r = new Record(UUID.randomUUID().toString(), file.id(), uniqueName(name, null),
                    req.description() == null ? "" : req.description().trim(), cat.id(), tagIds,
                    List.copyOf(new LinkedHashSet<>(req.ports())), blankToNull(req.ip()), range[0], range[1], p.stats(),
                    req.createdBy(), now, now);
            put(r);
        }
        onChange.run();
        return r;
    }

    public Record updateRecord(String id, UpdateRequest req) {
        Record old = requireRecord(id);
        String name = req.name() == null ? "" : req.name().trim();
        if (name.isEmpty()) throw new ApiException(422, "NAME_REQUIRED", "Name is required");
        PcapFile f = requireFile(old.fileId());
        Category cat = requireCategory(old.categoryId());
        List<Warning> errors = new ArrayList<>();
        long[] range = checkRange(f.summary(), req.startUs(), req.endUs(), errors);
        if (!errors.isEmpty()) throw new ApiException(422, errors.get(0).code(), Map.of("errors", errors), "Validation failed");
        Integer ip = old.ip() == null ? null : Ipv4Packet.parseIp(old.ip());
        RecordStats stats = compute(localCopy(f), cat.mode(), old.ports(), ip, range[0], range[1]).stats();
        if (stats.messageCount() == 0) throw new ApiException(422, "NO_MESSAGES", "No messages in the selected range");

        Record r;
        synchronized (this) {
            if (findByName(name, id) != null) throw new ApiException(409, "NAME_EXISTS", Map.of("name", name), "Name already used");
            Record cur = requireRecord(id);
            r = new Record(id, cur.fileId(), name, req.description() == null ? "" : req.description().trim(),
                    cur.categoryId(), cur.tagIds(), cur.ports(), cur.ip(), range[0], range[1], stats,
                    cur.createdBy(), cur.createdAtMs(), System.currentTimeMillis());
            put(r);
        }
        onChange.run();
        return r;
    }

    public void deleteRecord(String id) {
        synchronized (this) {
            requireRecord(id);
            deleteRecordAndOrphanFile(id);
        }
        onChange.run();
    }

    /** For downloads. */
    public PcapFile fileOfRecord(String recordId) {
        return requireFile(requireRecord(recordId).fileId());
    }

    private void deleteRecordAndOrphanFile(String id) {
        Record r = repo.record(id);
        if (r == null) return;
        try {
            repo.deleteRecord(id);
            boolean stillUsed = repo.records().stream().anyMatch(x -> x.fileId().equals(r.fileId()));
            if (!stillUsed) {
                PcapFile f = repo.file(r.fileId());
                repo.deleteFile(r.fileId());
                if (f != null) cache.forget(f.objectKey());
            }
        } catch (IOException e) {
            throw storage(e);
        }
    }

    private List<String> validTags(List<String> tagIds, String categoryId) {
        if (tagIds == null) return List.of();
        Map<String, Tag> byId = new LinkedHashMap<>();
        for (Tag t : repo.tags()) byId.put(t.id(), t);
        List<String> out = new ArrayList<>();
        for (String id : new LinkedHashSet<>(tagIds)) {
            Tag t = byId.get(id);
            if (t == null) continue;
            if (t.categoryId() != null && !t.categoryId().equals(categoryId)) {
                throw new ApiException(422, "TAG_NOT_IN_CATEGORY", Map.of("tag", t.name()), "Tag belongs to another category");
            }
            out.add(id);
        }
        return out;
    }

    private Record findByName(String name, String exceptId) {
        for (Record r : repo.records()) {
            if (r.name().equalsIgnoreCase(name) && !r.id().equals(exceptId)) return r;
        }
        return null;
    }

    /** Like a file explorer: "name", then "name (1)", "name (2)", … */
    private String uniqueName(String name, String exceptId) {
        if (findByName(name, exceptId) == null) return name;
        for (int i = 1; ; i++) {
            String candidate = name + " (" + i + ")";
            if (findByName(candidate, exceptId) == null) return candidate;
        }
    }

    private List<String> recordNamesUsing(String fileId) {
        return repo.records().stream().filter(r -> r.fileId().equals(fileId)).map(Record::name).toList();
    }

    private void put(Record r) {
        try {
            repo.putRecord(r);
        } catch (IOException e) {
            throw storage(e);
        }
    }

    // ------------------------------------------------------------------ categories

    public Category createCategory(String name, ConnectionMode mode) {
        String n = requireName(name);
        if (mode == null) throw new ApiException(422, "MODE_REQUIRED", "Connection mode is required");
        Category c;
        synchronized (this) {
            if (repo.categories().stream().anyMatch(x -> x.name().equalsIgnoreCase(n))) {
                throw new ApiException(409, "CATEGORY_EXISTS", Map.of("name", n), "Category exists");
            }
            c = new Category(UUID.randomUUID().toString(), n, mode, System.currentTimeMillis());
            try {
                repo.putCategory(c);
            } catch (IOException e) {
                throw storage(e);
            }
        }
        onChange.run();
        return c;
    }

    /** Only the name can change; the mode is fixed. */
    public Category renameCategory(String id, String name) {
        String n = requireName(name);
        Category c;
        synchronized (this) {
            Category old = requireCategory(id);
            if (repo.categories().stream().anyMatch(x -> x.name().equalsIgnoreCase(n) && !x.id().equals(id))) {
                throw new ApiException(409, "CATEGORY_EXISTS", Map.of("name", n), "Category exists");
            }
            c = new Category(id, n, old.mode(), old.createdAtMs());
            try {
                repo.putCategory(c);
            } catch (IOException e) {
                throw storage(e);
            }
        }
        onChange.run();
        return c;
    }

    public int categoryUsage(String id) {
        requireCategory(id);
        return (int) repo.records().stream().filter(r -> r.categoryId().equals(id)).count();
    }

    /** Deletes the category, all its records, its tags and any pcap files no longer used. */
    public void deleteCategory(String id) {
        synchronized (this) {
            requireCategory(id);
            for (Record r : repo.records()) {
                if (r.categoryId().equals(id)) deleteRecordAndOrphanFile(r.id());
            }
            try {
                repo.saveTags(repo.tags().stream().filter(t -> !id.equals(t.categoryId())).toList());
                repo.deleteCategory(id);
            } catch (IOException e) {
                throw storage(e);
            }
        }
        onChange.run();
    }

    // ------------------------------------------------------------------ tags

    public Tag createTag(String name, String categoryId) {
        String n = requireName(name);
        String cat = blankToNull(categoryId);
        Tag t;
        synchronized (this) {
            if (cat != null) requireCategory(cat);
            checkTagName(n, cat, null);
            t = new Tag(UUID.randomUUID().toString(), n, cat);
            List<Tag> all = repo.tags();
            all.add(t);
            saveTags(all);
        }
        onChange.run();
        return t;
    }

    public Tag renameTag(String id, String name) {
        String n = requireName(name);
        Tag t;
        synchronized (this) {
            List<Tag> all = repo.tags();
            int i = indexOfTag(all, id);
            Tag old = all.get(i);
            checkTagName(n, old.categoryId(), id);
            t = new Tag(id, n, old.categoryId());
            all.set(i, t);
            saveTags(all);
        }
        onChange.run();
        return t;
    }

    /** Deletes the tag and removes it from every record. */
    public void deleteTag(String id) {
        synchronized (this) {
            List<Tag> all = repo.tags();
            all.remove(indexOfTag(all, id));
            for (Record r : repo.records()) {
                if (r.tagIds().contains(id)) {
                    List<String> tags = new ArrayList<>(r.tagIds());
                    tags.remove(id);
                    put(r.withTags(tags));
                }
            }
            saveTags(all);
        }
        onChange.run();
    }

    private void checkTagName(String name, String categoryId, String exceptId) {
        for (Tag t : repo.tags()) {
            boolean sameScope = categoryId == null ? t.categoryId() == null : categoryId.equals(t.categoryId());
            if (sameScope && t.name().equalsIgnoreCase(name) && !t.id().equals(exceptId)) {
                throw new ApiException(409, "TAG_EXISTS", Map.of("name", name), "Tag exists");
            }
        }
    }

    private static int indexOfTag(List<Tag> all, String id) {
        for (int i = 0; i < all.size(); i++) if (all.get(i).id().equals(id)) return i;
        throw ApiException.notFound("Tag");
    }

    private void saveTags(List<Tag> all) {
        try {
            repo.saveTags(all);
        } catch (IOException e) {
            throw storage(e);
        }
    }

    // ------------------------------------------------------------------ ScriptSource (for the replay engine)

    @Override
    public RecordInfo describe(String recordId) {
        Record r = repo.record(recordId);
        if (r == null) throw new ReplayFailure("RECORD_NOT_FOUND", Map.of("recordId", recordId), "Record not found");
        Category c = repo.category(r.categoryId());
        if (c == null) throw new ReplayFailure("RECORD_NOT_FOUND", Map.of("recordId", recordId), "Category not found");
        return new RecordInfo(r.id(), r.name(), c.name(), c.mode());
    }

    @Override
    public Prepared prepare(String recordId) {
        RecordInfo info = describe(recordId);
        Record r = repo.record(recordId);
        PcapFile f = repo.file(r.fileId());
        if (f == null) throw new ReplayFailure("PREPARE_FAILED", Map.of("reason", "file missing"), "pcap file missing");
        try {
            Path path = cache.get(f.objectKey());
            Integer ip = r.ip() == null ? null : Ipv4Packet.parseIp(r.ip());
            ExtractionResult res = extractor.extract(path, new ExtractionFilter(info.mode(), new HashSet<>(r.ports()), ip,
                    r.startUs() * 1000, r.endUs() * 1000 + 999));
            return new Prepared(info, r.startUs(), res.script(), Histogram.of(res.script(), maxBins));
        } catch (IOException e) {
            throw new ReplayFailure("PREPARE_FAILED", Map.of("reason", String.valueOf(e.getMessage())), "Cannot load pcap");
        }
    }

    // ------------------------------------------------------------------ helpers

    private Record requireRecord(String id) {
        Record r = repo.record(id);
        if (r == null) throw ApiException.notFound("Record");
        return r;
    }

    private PcapFile requireFile(String id) {
        PcapFile f = id == null ? null : repo.file(id);
        if (f == null) throw ApiException.notFound("File");
        return f;
    }

    private Category requireCategory(String id) {
        Category c = id == null ? null : repo.category(id);
        if (c == null) throw ApiException.notFound("Category");
        return c;
    }

    private static String requireName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) throw new ApiException(422, "NAME_REQUIRED", "Name is required");
        return n;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static ApiException storage(IOException e) {
        return new ApiException(503, "STORAGE_ERROR", Map.of("reason", String.valueOf(e.getMessage())), "Storage error");
    }
}
