package com.pcapplayer.icd.analysis;

import com.pcapplayer.icd.decode.DecodedMessage;

import java.util.ArrayList;
import java.util.List;

/** Keeps the first {@code limit} messages, optionally of one opcode only. */
public final class CollectingAnalyzer implements MessageAnalyzer {

    private final int limit;
    private final Long opcode;
    private final List<DecodedMessage> messages = new ArrayList<>();

    /** @param opcode keep only this opcode; null for all */
    public CollectingAnalyzer(int limit, Long opcode) {
        this.limit = limit;
        this.opcode = opcode;
    }

    @Override
    public void onMessage(DecodedMessage message) {
        if (messages.size() < limit && (opcode == null || opcode == message.opcode())) messages.add(message);
    }

    public List<DecodedMessage> messages() {
        return messages;
    }
}
