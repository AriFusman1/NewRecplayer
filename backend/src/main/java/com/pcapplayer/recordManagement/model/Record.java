package com.pcapplayer.recordManagement.model;

import java.util.List;

/**
 * A replayable slice of a pcap file.
 *
 * @param ip      optional IPv4 filter (same endpoint as the ports), null = all IPs
 * @param startUs range start, epoch µs (inclusive)
 * @param endUs   range end, epoch µs (inclusive)
 */
public record Record(String id, String fileId, String name, String description, String categoryId,
                     List<String> tagIds, List<Integer> ports, String ip, long startUs, long endUs,
                     RecordStats stats, String createdBy, long createdAtMs, long updatedAtMs) {

    public Record withCategory(String newCategoryId) {
        return new Record(id, fileId, name, description, newCategoryId, tagIds, ports, ip, startUs, endUs, stats,
                createdBy, createdAtMs, System.currentTimeMillis());
    }

    public Record withTags(List<String> tags) {
        return new Record(id, fileId, name, description, categoryId, tags, ports, ip, startUs, endUs, stats,
                createdBy, createdAtMs, System.currentTimeMillis());
    }
}
