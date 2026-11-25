package com.pcapplayer.icd.decode;

import com.pcapplayer.icd.analysis.MessageAnalyzer;
import com.pcapplayer.icd.model.IcdDefinition;
import com.pcapplayer.pcapProcessing.script.ReplayScript;
import com.pcapplayer.pcapProcessing.script.ScriptFlow;
import com.pcapplayer.pcapProcessing.script.ScriptStep;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decodes every message of a replay script with an ICD and hands each one to the analyzers.
 * <p>
 * It works on the script, not on raw pcap packets: the script's {@code Send} steps are exactly the
 * bytes playback sends (IP fragments and TCP segments already reassembled), so step indexes in
 * {@link ByteSpan}s are what playback patches. It does not depend on playback: anything that has a
 * script (the debug endpoint, later auto-tagging over a whole pcap) can run it.
 */
public final class ScriptDecoder {

    private ScriptDecoder() {
    }

    /**
     * @param rangeStartNanos epoch nanos of the script's offset 0, to give messages their capture time
     * @return what was decoded and what could not be
     */
    public static DecodeStats decode(ReplayScript script, long rangeStartNanos, IcdDefinition icd, MessageAnalyzer... analyzers) {
        MessageDecoder decoder = new MessageDecoder(icd);
        DecodeStats stats = new DecodeStats(icd.id());
        List<ScriptStep> steps = script.steps();
        Map<Integer, FlowFramer> framers = new HashMap<>();

        for (int i = 0; i < steps.size(); i++) {
            ScriptStep step = steps.get(i);

            if (step instanceof ScriptStep.Send send) {
                framerOf(send.flowId(), framers, script, decoder, stats, rangeStartNanos, analyzers).add(i, send.data());
            } else if (step instanceof ScriptStep.Gap gap) {
                FlowFramer framer = framers.get(gap.flowId());
                if (framer != null) framer.reset("a capture gap of " + gap.bytes() + " bytes");
            }
        }
        for (FlowFramer framer : framers.values()) framer.reset("the end of the record");
        for (MessageAnalyzer analyzer : analyzers) analyzer.onEnd(stats);
        return stats;
    }

    /** Every decoded message, in the order they complete (tests and small records). */
    public static List<DecodedMessage> decodeAll(ReplayScript script, long rangeStartNanos, IcdDefinition icd) {
        List<DecodedMessage> messages = new ArrayList<>();
        decode(script, rangeStartNanos, icd, messages::add);
        return messages;
    }

    private static FlowFramer framerOf(int flowId, Map<Integer, FlowFramer> framers, ReplayScript script, MessageDecoder decoder,
                                       DecodeStats stats, long rangeStartNanos, MessageAnalyzer[] analyzers) {
        return framers.computeIfAbsent(flowId, id -> {
            ScriptFlow flow = id < script.flows().size() ? script.flows().get(id) : null;

            return new FlowFramer(decoder, id, flow, stats,
                    step -> rangeStartNanos + script.steps().get(step).offsetNanos(),
                    message -> {
                        for (MessageAnalyzer analyzer : analyzers) analyzer.onMessage(message);
                    });
        });
    }
}
