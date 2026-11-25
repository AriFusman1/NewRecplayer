package com.pcapplayer.recordManagement.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.pcapplayer.autotag.job.TagJob;
import com.pcapplayer.autotag.rules.CategoryTagRules;
import com.pcapplayer.common.Json;
import com.pcapplayer.icd.profile.TimeFixProfile;
import com.pcapplayer.persistence.BlobStore;
import com.pcapplayer.playback.model.HistoryEntry;
import com.pcapplayer.recordManagement.model.Category;
import com.pcapplayer.recordManagement.model.PcapFile;
import com.pcapplayer.recordManagement.model.Record;
import com.pcapplayer.recordManagement.model.SavedPlaylist;
import com.pcapplayer.recordManagement.model.Tag;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * All metadata, held in memory and written through to the blob store on every change.
 * <pre>
 * pcaps/{fileId}.pcap|pcapng   the capture
 * pcaps/{fileId}.json          {@link PcapFile}
 * records/{recordId}.json      {@link Record}
 * categories/{id}.json         {@link Category}
 * playlists/{id}.json          {@link SavedPlaylist}
 * icds/{icdId}.json            an uploaded ICD, exactly as uploaded (JSON ICD format)
 * timefix/{categoryId}.json    {@link TimeFixProfile} of a category
 * tagrules/{categoryId}.json   {@link CategoryTagRules}: the auto-tagging rules of a category
 * tagjobs/{jobId}.json         {@link TagJob}: an auto-tagging run and its per-file results
 * tags.json                    [{@link Tag}]
 * history.json                 [{@link HistoryEntry}] newest first
 * </pre>
 * Guarded by a read/write lock: reads run concurrently and return copies; writes are exclusive.
 * Multi-step mutations run under {@link #exclusive}.
 */
public class CatalogRepository {

    private static final Logger LOG = Logger.getLogger(CatalogRepository.class.getName());
    private static final String JSON = "application/json";
    private static final String CATEGORIES = "categories/";
    private static final String PCAPS = "pcaps/";
    private static final String RECORDS = "records/";
    private static final String PLAYLISTS = "playlists/";
    private static final String ICDS = "icds/";
    private static final String TIMEFIX = "timefix/";
    private static final String TAGRULES = "tagrules/";
    private static final String TAGJOBS = "tagjobs/";
    private static final int LOAD_THREADS = 16;

    private final BlobStore store;
    private final int historySize;
    private final Map<String, Category> categories = new LinkedHashMap<>();
    private final Map<String, Record> records = new LinkedHashMap<>();
    private final Map<String, PcapFile> files = new LinkedHashMap<>();
    private final List<Tag> tags = new ArrayList<>();
    private final Map<String, SavedPlaylist> playlists = new LinkedHashMap<>();
    private final Map<String, byte[]> icdJson = new LinkedHashMap<>();
    private final Map<String, TimeFixProfile> profiles = new LinkedHashMap<>();
    private final Map<String, CategoryTagRules> tagRules = new LinkedHashMap<>();
    private final Map<String, TagJob> tagJobs = new LinkedHashMap<>();
    private final List<HistoryEntry> history = new ArrayList<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public CatalogRepository(BlobStore store, int historySize) {
        this.store = store;
        this.historySize = historySize;
    }

    /** I/O work done while holding the write lock. */
    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }

    private <T> T read(Supplier<T> work) {
        lock.readLock().lock();

        try {
            return work.get();
        } finally {
            lock.readLock().unlock();
        }
    }

    private void write(IoAction work) throws IOException {
        lock.writeLock().lock();

        try {
            work.run();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Runs {@code work} holding the write lock, so a check-then-write sequence across several
     * calls is atomic. The lock is reentrant: {@code work} may call any method of this class.
     */
    public <T> T exclusive(Supplier<T> work) {
        lock.writeLock().lock();

        try {
            return work.get();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Loads everything from the store (startup). */
    public void load() throws IOException {
        write(() -> {
            categories.clear();
            records.clear();
            files.clear();
            tags.clear();
            history.clear();
            playlists.clear();
            icdJson.clear();
            profiles.clear();
            tagRules.clear();
            tagJobs.clear();
            loadAll(CATEGORIES, Category.class, Category::id, categories);
            loadAll(PCAPS, PcapFile.class, PcapFile::id, files);
            loadAll(RECORDS, Record.class, Record::id, records);
            loadAll(PLAYLISTS, SavedPlaylist.class, SavedPlaylist::id, playlists);
            loadAll(TIMEFIX, TimeFixProfile.class, TimeFixProfile::categoryId, profiles);
            loadAll(TAGRULES, CategoryTagRules.class, CategoryTagRules::categoryId, tagRules);
            loadAll(TAGJOBS, TagJob.class, TagJob::id, tagJobs);
            loadRaw(ICDS, icdJson);
            byte[] tagsJson = store.get("tags.json");

            if (tagsJson != null) tags.addAll(Json.MAPPER.readValue(tagsJson, new TypeReference<List<Tag>>() {
            }));
            byte[] historyJson = store.get("history.json");

            if (historyJson != null) history.addAll(Json.MAPPER.readValue(historyJson, new TypeReference<List<HistoryEntry>>() {
            }));
            LOG.info("Loaded " + categories.size() + " categories, " + records.size() + " records, "
                    + files.size() + " files, " + tags.size() + " tags, " + playlists.size() + " playlists, " + history.size() + " history entries, "
                    + icdJson.size() + " ICDs, " + profiles.size() + " time-fix profiles");
        });
    }

    /**
     * Reads every {@code <prefix><id>.json} object into {@code into}, keyed by id. Objects are fetched
     * in parallel (one request each, which adds up on S3) but inserted in listing order.
     */
    private <T> void loadAll(String prefix, Class<T> type, Function<T, String> id, Map<String, T> into) throws IOException {
        List<String> keys = store.list(prefix).stream().filter(key -> key.endsWith(".json")).toList();
        if (keys.isEmpty()) return;
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(LOAD_THREADS, keys.size()));

        try {
            List<Future<T>> pending = new ArrayList<>();
            for (String key : keys) pending.add(pool.submit(() -> Json.read(store.get(key), type)));

            for (Future<T> pendingItem : pending) {
                T item = await(pendingItem);
                into.put(id.apply(item), item);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** Reads every {@code <prefix><id>.json} object as raw bytes, keyed by id. */
    private void loadRaw(String prefix, Map<String, byte[]> into) throws IOException {
        for (String key : store.list(prefix)) {
            if (!key.endsWith(".json")) continue;
            String id = key.substring(key.lastIndexOf('/') + 1, key.length() - ".json".length());
            byte[] bytes = store.get(key);
            if (bytes != null) into.put(id, bytes);
        }
    }

    private static <T> T await(Future<T> future) throws IOException {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while loading metadata", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException ioError) throw ioError;
            throw new IOException(e.getCause());
        }
    }

    private static String key(String prefix, String id) {
        return prefix + id + ".json";
    }

    // ---------------------------------------------------------------- reads

    public List<Category> categories() {
        return read(() -> new ArrayList<>(categories.values()));
    }

    public Category category(String id) {
        return read(() -> categories.get(id));
    }

    public List<Record> records() {
        return read(() -> new ArrayList<>(records.values()));
    }

    public Record record(String id) {
        return read(() -> records.get(id));
    }

    public List<PcapFile> files() {
        return read(() -> new ArrayList<>(files.values()));
    }

    public PcapFile file(String id) {
        return read(() -> files.get(id));
    }

    public PcapFile fileBySha(String sha256) {
        return read(() -> files.values().stream().filter(file -> file.sha256().equals(sha256)).findFirst().orElse(null));
    }

    public List<Tag> tags() {
        return read(() -> new ArrayList<>(tags));
    }

    public List<SavedPlaylist> playlists() {
        return read(() -> new ArrayList<>(playlists.values()));
    }

    public SavedPlaylist playlist(String id) {
        return read(() -> playlists.get(id));
    }

    public List<HistoryEntry> history() {
        return read(() -> new ArrayList<>(history));
    }

    /** The uploaded ICD files by id (the arrays are not copied: treat them as read-only). */
    public Map<String, byte[]> icdJson() {
        return read(() -> new LinkedHashMap<>(icdJson));
    }

    public byte[] icdJson(String id) {
        return read(() -> icdJson.get(id));
    }

    public TimeFixProfile profile(String categoryId) {
        return read(() -> profiles.get(categoryId));
    }

    /** The category's auto-tagging rules, or null if it has none saved. */
    public CategoryTagRules tagRules(String categoryId) {
        return read(() -> tagRules.get(categoryId));
    }

    public List<TagJob> tagJobs() {
        return read(() -> new ArrayList<>(tagJobs.values()));
    }

    public TagJob tagJob(String id) {
        return read(() -> tagJobs.get(id));
    }

    // ---------------------------------------------------------------- writes

    public void putCategory(Category category) throws IOException {
        write(() -> {
            store.put(key(CATEGORIES, category.id()), Json.bytes(category), JSON);
            categories.put(category.id(), category);
        });
    }

    public void deleteCategory(String id) throws IOException {
        write(() -> {
            store.delete(key(CATEGORIES, id));
            categories.remove(id);
        });
    }

    public void putRecord(Record record) throws IOException {
        write(() -> {
            store.put(key(RECORDS, record.id()), Json.bytes(record), JSON);
            records.put(record.id(), record);
        });
    }

    public void deleteRecord(String id) throws IOException {
        write(() -> {
            store.delete(key(RECORDS, id));
            records.remove(id);
        });
    }

    /** Uploads the capture first, then its metadata, so a listed file always has its bytes. */
    public void putFile(PcapFile file, Path localCopy) throws IOException {
        write(() -> {
            store.putFile(file.objectKey(), localCopy, "application/vnd.tcpdump.pcap");
            store.put(file.metaKey(), Json.bytes(file), JSON);
            files.put(file.id(), file);
        });
    }

    public void deleteFile(String id) throws IOException {
        write(() -> {
            PcapFile file = files.get(id);
            if (file == null) return;
            store.delete(file.metaKey());
            store.delete(file.objectKey());
            files.remove(id);
        });
    }

    public void putPlaylist(SavedPlaylist playlist) throws IOException {
        write(() -> {
            store.put(key(PLAYLISTS, playlist.id()), Json.bytes(playlist), JSON);
            playlists.put(playlist.id(), playlist);
        });
    }

    public void deletePlaylist(String id) throws IOException {
        write(() -> {
            store.delete(key(PLAYLISTS, id));
            playlists.remove(id);
        });
    }

    public void putIcdJson(String id, byte[] json) throws IOException {
        write(() -> {
            store.put(key(ICDS, id), json, JSON);
            icdJson.put(id, json);
        });
    }

    public void deleteIcdJson(String id) throws IOException {
        write(() -> {
            store.delete(key(ICDS, id));
            icdJson.remove(id);
        });
    }

    public void putProfile(TimeFixProfile profile) throws IOException {
        write(() -> {
            store.put(key(TIMEFIX, profile.categoryId()), Json.bytes(profile), JSON);
            profiles.put(profile.categoryId(), profile);
        });
    }

    /** Deletes a category's profile, if it has one. */
    public void deleteProfile(String categoryId) throws IOException {
        write(() -> {
            if (profiles.remove(categoryId) != null) store.delete(key(TIMEFIX, categoryId));
        });
    }

    public void putTagRules(CategoryTagRules rules) throws IOException {
        write(() -> {
            store.put(key(TAGRULES, rules.categoryId()), Json.bytes(rules), JSON);
            tagRules.put(rules.categoryId(), rules);
        });
    }

    /** Deletes a category's auto-tagging rules, if it has any. */
    public void deleteTagRules(String categoryId) throws IOException {
        write(() -> {
            if (tagRules.remove(categoryId) != null) store.delete(key(TAGRULES, categoryId));
        });
    }

    public void putTagJob(TagJob job) throws IOException {
        write(() -> {
            store.put(key(TAGJOBS, job.id()), Json.bytes(job), JSON);
            tagJobs.put(job.id(), job);
        });
    }

    public void saveTags(Collection<Tag> newTags) throws IOException {
        write(() -> {
            List<Tag> copy = new ArrayList<>(newTags);
            store.put("tags.json", Json.bytes(copy), JSON);
            tags.clear();
            tags.addAll(copy);
        });
    }

    public void addHistory(HistoryEntry e) throws IOException {
        write(() -> {
            List<HistoryEntry> copy = new ArrayList<>(history);
            copy.add(0, e);
            while (copy.size() > historySize) copy.remove(copy.size() - 1);
            store.put("history.json", Json.bytes(copy), JSON);
            history.clear();
            history.addAll(copy);
        });
    }
}
