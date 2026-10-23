package com.pcapplayer.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.pcapplayer.replay.HistoryEntry;
import com.pcapplayer.storage.BlobStore;
import com.pcapplayer.util.Json;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * All metadata, held in memory and written through to the blob store on every change.
 * <pre>
 * pcaps/{fileId}.pcap|pcapng   the capture
 * pcaps/{fileId}.json          {@link PcapFile}
 * records/{recordId}.json      {@link Record}
 * categories/{id}.json         {@link Category}
 * tags.json                    [{@link Tag}]
 * history.json                 [{@link HistoryEntry}] newest first
 * </pre>
 * Callers serialize mutations (see {@link CatalogService}); reads return copies.
 */
public final class CatalogRepository {

    private static final Logger LOG = Logger.getLogger(CatalogRepository.class.getName());
    private static final String JSON = "application/json";

    private final BlobStore store;
    private final int historySize;
    private final Map<String, Category> categories = new LinkedHashMap<>();
    private final Map<String, Record> records = new LinkedHashMap<>();
    private final Map<String, PcapFile> files = new LinkedHashMap<>();
    private final List<Tag> tags = new ArrayList<>();
    private final List<HistoryEntry> history = new ArrayList<>();

    public CatalogRepository(BlobStore store, int historySize) {
        this.store = store;
        this.historySize = historySize;
    }

    /** Loads everything from the store (startup). */
    public synchronized void load() throws IOException {
        categories.clear();
        records.clear();
        files.clear();
        tags.clear();
        history.clear();
        for (String key : store.list("categories/")) {
            if (!key.endsWith(".json")) continue;
            Category c = Json.read(store.get(key), Category.class);
            categories.put(c.id(), c);
        }
        for (String key : store.list("pcaps/")) {
            if (!key.endsWith(".json")) continue;
            PcapFile f = Json.read(store.get(key), PcapFile.class);
            files.put(f.id(), f);
        }
        for (String key : store.list("records/")) {
            if (!key.endsWith(".json")) continue;
            Record r = Json.read(store.get(key), Record.class);
            records.put(r.id(), r);
        }
        byte[] t = store.get("tags.json");
        if (t != null) tags.addAll(Json.MAPPER.readValue(t, new TypeReference<List<Tag>>() {
        }));
        byte[] h = store.get("history.json");
        if (h != null) history.addAll(Json.MAPPER.readValue(h, new TypeReference<List<HistoryEntry>>() {
        }));
        LOG.info("Loaded " + categories.size() + " categories, " + records.size() + " records, "
                + files.size() + " files, " + tags.size() + " tags, " + history.size() + " history entries");
    }

    // ---------------------------------------------------------------- reads

    public synchronized List<Category> categories() {
        return new ArrayList<>(categories.values());
    }

    public synchronized Category category(String id) {
        return categories.get(id);
    }

    public synchronized List<Record> records() {
        return new ArrayList<>(records.values());
    }

    public synchronized Record record(String id) {
        return records.get(id);
    }

    public synchronized List<PcapFile> files() {
        return new ArrayList<>(files.values());
    }

    public synchronized PcapFile file(String id) {
        return files.get(id);
    }

    public synchronized PcapFile fileBySha(String sha) {
        for (PcapFile f : files.values()) if (f.sha256().equals(sha)) return f;
        return null;
    }

    public synchronized List<Tag> tags() {
        return new ArrayList<>(tags);
    }

    public synchronized List<HistoryEntry> history() {
        return new ArrayList<>(history);
    }

    // ---------------------------------------------------------------- writes

    public synchronized void putCategory(Category c) throws IOException {
        store.put("categories/" + c.id() + ".json", Json.bytes(c), JSON);
        categories.put(c.id(), c);
    }

    public synchronized void deleteCategory(String id) throws IOException {
        store.delete("categories/" + id + ".json");
        categories.remove(id);
    }

    public synchronized void putRecord(Record r) throws IOException {
        store.put("records/" + r.id() + ".json", Json.bytes(r), JSON);
        records.put(r.id(), r);
    }

    public synchronized void deleteRecord(String id) throws IOException {
        store.delete("records/" + id + ".json");
        records.remove(id);
    }

    /** Uploads the capture first, then its metadata, so a listed file always has its bytes. */
    public synchronized void putFile(PcapFile f, Path localCopy) throws IOException {
        store.putFile(f.objectKey(), localCopy, "application/vnd.tcpdump.pcap");
        store.put(f.metaKey(), Json.bytes(f), JSON);
        files.put(f.id(), f);
    }

    public synchronized void deleteFile(String id) throws IOException {
        PcapFile f = files.get(id);
        if (f == null) return;
        store.delete(f.metaKey());
        store.delete(f.objectKey());
        files.remove(id);
    }

    public synchronized void saveTags(Collection<Tag> newTags) throws IOException {
        List<Tag> copy = new ArrayList<>(newTags);
        store.put("tags.json", Json.bytes(copy), JSON);
        tags.clear();
        tags.addAll(copy);
    }

    public synchronized void addHistory(HistoryEntry e) throws IOException {
        List<HistoryEntry> copy = new ArrayList<>(history);
        copy.add(0, e);
        while (copy.size() > historySize) copy.remove(copy.size() - 1);
        store.put("history.json", Json.bytes(copy), JSON);
        history.clear();
        history.addAll(copy);
    }

    public BlobStore store() {
        return store;
    }
}
