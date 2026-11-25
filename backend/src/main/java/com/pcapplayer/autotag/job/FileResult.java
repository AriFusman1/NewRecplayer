package com.pcapplayer.autotag.job;

import com.pcapplayer.recordManagement.model.MessageTypeCount;

import java.util.List;

/**
 * The outcome for one file.
 *
 * @param index     position of the file in the job's input list
 * @param recordIds the record created, or the existing records updated
 * @param error     why the file failed; null otherwise
 */
public record FileResult(int index, String name, String path, long size, boolean gzipped, EFileStatus status,
                         long totalMessages, List<MessageTypeCount> counts, long parseErrors, long unknownOpcodes,
                         List<String> matchedRules, List<String> recordIds, String error) {

    static FileResult notAPcap(int index, JobInput input, boolean gzipped) {
        return new FileResult(index, input.name(), input.path(), input.size(), gzipped, EFileStatus.NOT_A_PCAP, 0, List.of(), 0, 0,
                List.of(), List.of(), null);
    }

    static FileResult failed(int index, JobInput input, boolean gzipped, String error) {
        return new FileResult(index, input.name(), input.path(), input.size(), gzipped, EFileStatus.FAILED, 0, List.of(), 0, 0,
                List.of(), List.of(), error);
    }
}
