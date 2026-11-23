package com.pcapplayer.pcapProcessing.extract;

import com.pcapplayer.pcapProcessing.script.ScriptStep;

import java.util.ArrayList;
import java.util.List;

/** The script being built, with running message totals. */
final class ScriptStepsBuilder {

    private final ExtractionFilter filter;
    final List<ScriptStep> steps = new ArrayList<>();
    long bytes;
    long messages;
    long first = Long.MAX_VALUE;
    long last = Long.MIN_VALUE;

    ScriptStepsBuilder(ExtractionFilter filter) {
        this.filter = filter;
    }

    void add(ScriptStep step) {
        steps.add(step);

        if (step instanceof ScriptStep.Send send) {
            long ts = filter.startNanos() + send.offsetNanos();
            bytes += send.data().length;
            messages++;
            first = Math.min(first, ts);
            last = Math.max(last, ts);
        }
    }
}
