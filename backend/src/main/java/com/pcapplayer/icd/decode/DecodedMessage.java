package com.pcapplayer.icd.decode;

import com.pcapplayer.pcapProcessing.script.ScriptFlow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One message cut out of a flow and decoded. This is the generic output of the decoder, used by time
 * fixing during playback and by any {@code MessageAnalyzer} (debug output, later auto-tagging).
 *
 * @param flow           the recorded flow; null if the script does not track flows
 * @param captureTsNanos capture time (epoch nanos) of the packet holding the message's first byte
 * @param firstStep      script step holding the first byte
 * @param lastStep       script step holding the last byte
 * @param length         message size in bytes, header included
 * @param fields         path → value, in message order
 * @param slots          path → position and storage of every number (not strings), for patching
 * @param pieces         where the message's bytes are, in order: concatenated they are the message
 */
public record DecodedMessage(long opcode, String name, int flowId, ScriptFlow flow, long captureTsNanos,
                             int firstStep, int lastStep, int length, int trailingBytes,
                             Map<String, FieldValue> fields, Map<String, FieldSlot> slots, List<ByteSpan> pieces) {

    /**
     * Maps bytes of the message back to the packets they came from. A range that crosses a packet
     * boundary gives one span per packet.
     *
     * @param offset from the start of the message
     */
    public List<ByteSpan> locate(int offset, int size) {
        List<ByteSpan> spans = new ArrayList<>();
        int pieceStart = 0;

        for (ByteSpan piece : pieces) {
            int pieceEnd = pieceStart + piece.length();
            int from = Math.max(offset, pieceStart);
            int to = Math.min(offset + size, pieceEnd);
            if (from < to) spans.add(new ByteSpan(piece.step(), piece.offset() + (from - pieceStart), to - from));
            if (pieceEnd >= offset + size) break;
            pieceStart = pieceEnd;
        }
        return spans;
    }

    /** Where the field at {@code path} is in the packets; empty if it has no slot (strings, unknown paths). */
    public List<ByteSpan> location(String path) {
        FieldSlot slot = slots.get(path);
        return slot == null ? List.of() : locate(slot.offset(), slot.size());
    }

    /** {@link #location} of every number in the message. */
    public Map<String, List<ByteSpan>> locations() {
        Map<String, List<ByteSpan>> all = new LinkedHashMap<>();
        for (String path : slots.keySet()) all.put(path, location(path));
        return all;
    }
}
