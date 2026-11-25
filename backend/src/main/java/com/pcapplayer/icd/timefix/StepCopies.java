package com.pcapplayer.icd.timefix;

import com.pcapplayer.pcapProcessing.script.ScriptStep;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writable copies of a script's steps, made on first use. The script's own byte arrays are shared
 * (cache, concurrent replays) and are never written to; patches go into these copies, and playback
 * sends the copy instead of the original. One instance per replay; not thread-safe.
 */
public final class StepCopies implements StepBytes {

    private final List<ScriptStep> steps;
    private final Map<Integer, byte[]> copies = new HashMap<>();

    public StepCopies(List<ScriptStep> steps) {
        this.steps = steps;
    }

    @Override
    public byte[] bytes(int step) {
        return copies.computeIfAbsent(step, index -> ((ScriptStep.Send) steps.get(index)).data().clone());
    }

    /** Removes and returns the copy of {@code step}, or null if it was never patched. */
    public byte[] take(int step) {
        return copies.remove(step);
    }

    /** Drops every copy (after a seek: the next sends start from the original bytes again). */
    public void clear() {
        copies.clear();
    }

    /** Copies still held (patched but not sent yet). */
    public int held() {
        return copies.size();
    }
}
