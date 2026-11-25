package com.pcapplayer.icd.analysis;

import com.pcapplayer.icd.decode.DecodedMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Counts decoded messages per opcode ("message type Z appears N times"). */
public final class MessageCountAnalyzer implements MessageAnalyzer {

    public record OpcodeCount(long opcode, String name, long count) {
    }

    private final Map<Long, OpcodeCount> counts = new TreeMap<>();

    @Override
    public void onMessage(DecodedMessage message) {
        counts.merge(message.opcode(), new OpcodeCount(message.opcode(), message.name(), 1),
                (old, one) -> new OpcodeCount(old.opcode(), old.name(), old.count() + 1));
    }

    public long count(long opcode) {
        OpcodeCount count = counts.get(opcode);
        return count == null ? 0 : count.count();
    }

    /** Sorted by opcode. */
    public List<OpcodeCount> counts() {
        return new ArrayList<>(counts.values());
    }
}
