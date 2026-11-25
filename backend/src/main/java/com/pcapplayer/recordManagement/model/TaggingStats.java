package com.pcapplayer.recordManagement.model;

import java.util.List;

/**
 * Message statistics attached to a record by auto-tagging. They describe the whole file, decoded
 * with the category's ICD.
 *
 * @param counts         count per message type, sorted by opcode
 * @param parseErrors    messages whose bytes did not match their definition
 * @param unknownOpcodes headers with an opcode the ICD does not define
 * @param matchedRules   the rules the file matched in the run that wrote these stats
 * @param jobId          that run
 */
public record TaggingStats(String icdId, long totalMessages, List<MessageTypeCount> counts, long parseErrors,
                           long unknownOpcodes, List<String> matchedRules, String jobId, long taggedAtMs) {

    /** Messages of this type; 0 if none. */
    public long count(long opcode) {
        return counts.stream().filter(count -> count.opcode() == opcode).mapToLong(MessageTypeCount::count).findFirst().orElse(0);
    }
}
