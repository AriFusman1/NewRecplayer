package com.pcapplayer.extract;

import java.util.List;

/**
 * Output of {@link MessageExtractor}: the script plus statistics for the upload/edit summary.
 *
 * @param otherProtocolPackets packets matching the port/IP filter but of the other protocol (TCP vs UDP)
 */
public record ExtractionResult(ReplayScript script, long firstMessageNanos, long lastMessageNanos,
                               int connections, long otherProtocolPackets, List<Warning> warnings) {

    public long messageCount() {
        return script.messageCount();
    }
}
