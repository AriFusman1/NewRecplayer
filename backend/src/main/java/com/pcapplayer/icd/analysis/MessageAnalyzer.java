package com.pcapplayer.icd.analysis;

import com.pcapplayer.icd.decode.DecodeStats;
import com.pcapplayer.icd.decode.DecodedMessage;

/**
 * Consumes the decoded message stream. New uses of decoded messages (counting, matching field values
 * for auto-tagging, building time-fix plans) are new analyzers; the decoder does not change.
 */
@FunctionalInterface
public interface MessageAnalyzer {

    /** Called for every decoded message, in the order messages complete. */
    void onMessage(DecodedMessage message);

    /** Called once after the last message. */
    default void onEnd(DecodeStats stats) {
    }
}
