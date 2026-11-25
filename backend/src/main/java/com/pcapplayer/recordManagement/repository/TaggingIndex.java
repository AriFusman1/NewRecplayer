package com.pcapplayer.recordManagement.repository;

import com.pcapplayer.recordManagement.model.MessageTypeCount;
import com.pcapplayer.recordManagement.model.Record;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Indexes of the records' auto-tagging statistics, kept next to the records so the records list
 * can be filtered without reading every record:
 * <ul>
 *   <li>matched rule name (ignoring case) → record ids</li>
 *   <li>opcode → (record id → message count)</li>
 * </ul>
 * Not thread-safe: {@link CatalogRepository} updates and reads it under its lock.
 */
final class TaggingIndex {

    private final Map<String, Set<String>> byRule = new HashMap<>();
    private final Map<Long, Map<String, Long>> byOpcode = new HashMap<>();

    void clear() {
        byRule.clear();
        byOpcode.clear();
    }

    void add(Record record) {
        if (record.tagging() == null) return;

        for (String rule : record.tagging().matchedRules()) {
            byRule.computeIfAbsent(key(rule), name -> new HashSet<>()).add(record.id());
        }

        for (MessageTypeCount count : record.tagging().counts()) {
            byOpcode.computeIfAbsent(count.opcode(), opcode -> new HashMap<>()).put(record.id(), count.count());
        }
    }

    void remove(Record record) {
        if (record == null || record.tagging() == null) return;
        for (String rule : record.tagging().matchedRules()) removeFrom(byRule, key(rule), record.id());

        for (MessageTypeCount count : record.tagging().counts()) {
            Map<String, Long> counts = byOpcode.get(count.opcode());
            if (counts == null) continue;
            counts.remove(record.id());
            if (counts.isEmpty()) byOpcode.remove(count.opcode());
        }
    }

    /** Ids of records that matched this rule. */
    Set<String> withRule(String rule) {
        return byRule.getOrDefault(key(rule), Set.of());
    }

    /** Ids of records with at least {@code minCount} messages of this type. */
    Set<String> withCount(long opcode, long minCount) {
        Set<String> ids = new HashSet<>();
        byOpcode.getOrDefault(opcode, Map.of()).forEach((id, count) -> {
            if (count >= minCount) ids.add(id);
        });
        return ids;
    }

    private static void removeFrom(Map<String, Set<String>> index, String key, String id) {
        Set<String> ids = index.get(key);
        if (ids == null) return;
        ids.remove(id);
        if (ids.isEmpty()) index.remove(key);
    }

    private static String key(String rule) {
        return rule.trim().toLowerCase(Locale.ROOT);
    }
}
