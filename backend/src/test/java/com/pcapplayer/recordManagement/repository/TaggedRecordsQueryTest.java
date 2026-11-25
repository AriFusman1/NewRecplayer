package com.pcapplayer.recordManagement.repository;

import com.pcapplayer.persistence.filesystem.FileSystemBlobStore;
import com.pcapplayer.recordManagement.model.MessageTypeCount;
import com.pcapplayer.recordManagement.model.Record;
import com.pcapplayer.recordManagement.model.TaggingStats;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Record filters by matched rule and message counts, kept up to date through put, delete and reload. */
class TaggedRecordsQueryTest {

    @TempDir
    Path dir;

    private static Record record(String id, String category, TaggingStats tagging) {
        return new Record(id, "file", id, "", category, List.of(), List.of(1), null, 0, 1, null, "t", 0, 0, tagging);
    }

    private static TaggingStats stats(List<String> rules, long tracks) {
        return new TaggingStats("icd", tracks, List.of(new MessageTypeCount(2, "Track", tracks)), 0, 0, rules, "job", 0);
    }

    private static List<String> ids(List<Record> records) {
        return records.stream().map(Record::id).sorted().toList();
    }

    @Test
    void filtersByRuleAndCount() throws Exception {
        CatalogRepository repo = new CatalogRepository(new FileSystemBlobStore(dir), 20);
        repo.load();
        repo.putRecord(record("a", "c1", stats(List.of("Air"), 50)));
        repo.putRecord(record("b", "c1", stats(List.of("Air", "Busy"), 5)));
        repo.putRecord(record("c", "c2", stats(List.of("busy"), 100)));
        repo.putRecord(record("manual", "c1", null));

        assertEquals(List.of("a", "b"), ids(repo.taggedRecords(null, "air", null, 1)), "rule names ignore case");
        assertEquals(List.of("b", "c"), ids(repo.taggedRecords(null, "Busy", null, 1)));
        assertEquals(List.of("a", "c"), ids(repo.taggedRecords(null, null, 2L, 10)));
        assertEquals(List.of("a"), ids(repo.taggedRecords("c1", "Air", 2L, 10)));
        assertEquals(List.of(), ids(repo.taggedRecords(null, null, 99L, 1)));
        assertEquals(List.of("a", "b", "manual"), ids(repo.taggedRecords("c1", null, null, 1)));

        repo.putRecord(record("a", "c1", stats(List.of("Other"), 1))); // replaced: old entries leave the index
        assertEquals(List.of("b"), ids(repo.taggedRecords(null, "Air", null, 1)));
        assertEquals(List.of("c"), ids(repo.taggedRecords(null, null, 2L, 10)));

        repo.deleteRecord("c");
        assertEquals(List.of("b"), ids(repo.taggedRecords(null, "busy", null, 1)));

        CatalogRepository reloaded = new CatalogRepository(new FileSystemBlobStore(dir), 20);
        reloaded.load();
        assertEquals(List.of("a"), ids(reloaded.taggedRecords(null, "other", 2L, 1)));
    }
}
