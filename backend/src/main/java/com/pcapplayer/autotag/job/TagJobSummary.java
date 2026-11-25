package com.pcapplayer.autotag.job;

import com.pcapplayer.autotag.rules.TagRule;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** A job in the list of jobs: no inputs, no per-file results. */
public record TagJobSummary(String id, String categoryId, String categoryName, TagJobSource source, List<String> ruleNames,
                            boolean evaluateOnly, String createdBy, ETagJobStatus status, String failureReason, int filesTotal,
                            int filesDone, long bytesTotal, long bytesDone, long createdAtMs, long startedAtMs, long endedAtMs,
                            Map<EFileStatus, Long> statusCounts) {

    static TagJobSummary of(TagJob job) {
        Map<EFileStatus, Long> counts = new EnumMap<>(EFileStatus.class);
        for (FileResult result : job.results()) counts.merge(result.status(), 1L, Long::sum);
        return new TagJobSummary(job.id(), job.categoryId(), job.categoryName(), job.source(),
                job.rules().stream().map(TagRule::name).toList(), job.evaluateOnly(), job.createdBy(), job.status(),
                job.failureReason(), job.filesTotal(), job.filesDone(), job.bytesTotal(), job.bytesDone(), job.createdAtMs(),
                job.startedAtMs(), job.endedAtMs(), counts);
    }
}
