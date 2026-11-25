package com.pcapplayer.autotag.job;

import com.pcapplayer.autotag.rules.TagRule;

import java.util.List;

/**
 * A tagging run, as stored ({@code tagjobs/{id}.json}) and shown.
 *
 * @param rules         a snapshot of the rules when the job was created: later edits do not change it
 * @param evaluateOnly  run everything but save nothing
 * @param failureReason why it FAILED ("interrupted" after a restart); null otherwise
 * @param inputs        the files, in order
 * @param results       one per processed file, in completion order
 */
public record TagJob(String id, String categoryId, String categoryName, String icdId, TagJobSource source, List<TagRule> rules,
                     boolean evaluateOnly, String createdBy, ETagJobStatus status, String failureReason,
                     int filesDone, long bytesTotal, long bytesDone, long createdAtMs, long startedAtMs, long endedAtMs,
                     List<JobInput> inputs, List<FileResult> results) {

    public int filesTotal() {
        return inputs.size();
    }

    TagJob withStatus(ETagJobStatus newStatus, String reason, long startedMs, long endedMs) {
        return new TagJob(id, categoryId, categoryName, icdId, source, rules, evaluateOnly, createdBy, newStatus, reason,
                filesDone, bytesTotal, bytesDone, createdAtMs, startedMs, endedMs, inputs, results);
    }

    TagJob withResults(int done, long doneBytes, List<FileResult> newResults) {
        return new TagJob(id, categoryId, categoryName, icdId, source, rules, evaluateOnly, createdBy, status, failureReason,
                done, bytesTotal, doneBytes, createdAtMs, startedAtMs, endedAtMs, inputs, newResults);
    }
}
