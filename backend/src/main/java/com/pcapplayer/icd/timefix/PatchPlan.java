package com.pcapplayer.icd.timefix;

import java.util.BitSet;
import java.util.List;
import java.util.Map;

/**
 * Every message of a replay script to patch, indexed by the step that starts it. Built once before
 * playback ({@link TimeFixPlanner}); read-only afterwards, so one plan can serve concurrent replays.
 *
 * @param byFirstStep   step → the messages whose first byte is in that step
 * @param patchedSteps  every step some patch writes into (they are copied before sending)
 * @param maxSpan       the most steps any message's last step is after its first (0: every message in one packet)
 */
public record PatchPlan(Map<Integer, List<MessagePatch>> byFirstStep, BitSet patchedSteps, int messageCount, int maxSpan) {

    public List<MessagePatch> startingAt(int step) {
        return byFirstStep.getOrDefault(step, List.of());
    }

    public boolean writesInto(int step) {
        return patchedSteps.get(step);
    }

    public boolean empty() {
        return messageCount == 0;
    }
}
