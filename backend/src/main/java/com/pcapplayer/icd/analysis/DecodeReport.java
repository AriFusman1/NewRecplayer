package com.pcapplayer.icd.analysis;

import com.pcapplayer.icd.decode.ByteSpan;
import com.pcapplayer.icd.decode.DecodeStats;
import com.pcapplayer.icd.decode.DecodedMessage;
import com.pcapplayer.icd.decode.FieldValue;
import com.pcapplayer.icd.model.EValidity;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The debug view of a decoded record ({@code GET /api/records/{id}/decode}): counts per opcode, the
 * problems found, and the first messages with their fields and byte locations.
 */
public record DecodeReport(String icdId, int scriptSteps, DecodeStats.Summary stats,
                           List<MessageCountAnalyzer.OpcodeCount> counts, List<MessageView> messages) {

    /**
     * One message for display.
     *
     * @param fields    path → value; enums as "NAME (value)"
     * @param locations path → where the field's bytes are: [step, offset in step, length], one entry per packet
     */
    public record MessageView(long opcode, String name, String flow, long captureTsUs, int firstStep, int lastStep,
                              int length, int trailingBytes, Map<String, Object> fields, Map<String, List<ByteSpan>> locations) {
    }

    public static DecodeReport of(String icdId, int scriptSteps, DecodeStats stats, MessageCountAnalyzer counts,
                                  CollectingAnalyzer collected) {
        List<MessageView> messages = collected.messages().stream().map(DecodeReport::view).toList();
        return new DecodeReport(icdId, scriptSteps, stats.summary(), counts.counts(), messages);
    }

    private static MessageView view(DecodedMessage message) {
        Map<String, Object> fields = new LinkedHashMap<>();
        message.fields().forEach((path, value) -> fields.put(path, display(value)));
        String flow = message.flow() != null ? message.flow().describe() : "flow " + message.flowId();

        return new MessageView(message.opcode(), message.name(), flow, TimeUnit.NANOSECONDS.toMicros(message.captureTsNanos()),
                message.firstStep(), message.lastStep(), message.length(), message.trailingBytes(), fields, message.locations());
    }

    /**
     * Big UINT64 values as decimal strings (JavaScript numbers would lose precision). A scaled value
     * shows its real value; an invalid one says why, e.g. "200 (INVALIDITY_VALUE)".
     */
    private static Object display(FieldValue value) {
        Object plain = value.value() instanceof BigInteger big ? big.toString() : value.value();
        Object shown = value.scaled() != null ? value.scaled() : plain;
        if (value.enumName() != null) shown = value.enumName() + " (" + plain + ")";
        return value.validity() == EValidity.VALID ? shown : shown + " (" + value.validity() + ")";
    }
}
