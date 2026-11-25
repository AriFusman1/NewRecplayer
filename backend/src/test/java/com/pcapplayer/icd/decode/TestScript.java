package com.pcapplayer.icd.decode;

import com.pcapplayer.pcapProcessing.script.EConnectionMode;
import com.pcapplayer.pcapProcessing.script.ReplayScript;
import com.pcapplayer.pcapProcessing.script.ScriptFlow;
import com.pcapplayer.pcapProcessing.script.ScriptStep;

import java.util.ArrayList;
import java.util.List;

/** Builds a replay script by hand: one step per call, 1 ms apart, on flow 0 or 1. */
final class TestScript {

    static final long MS = 1_000_000;

    private final List<ScriptStep> steps = new ArrayList<>();

    TestScript send(int flowId, byte[] data) {
        steps.add(new ScriptStep.Send(steps.size() * MS, data, flowId));
        return this;
    }

    TestScript gap(int flowId) {
        steps.add(new ScriptStep.Gap(steps.size() * MS, 100, flowId));
        return this;
    }

    ReplayScript build() {
        List<ScriptFlow> flows = List.of(new ScriptFlow(0, true, 1, 1000, 2, 2000), new ScriptFlow(1, true, 1, 1001, 2, 2000));
        return new ReplayScript(EConnectionMode.TCP_SERVER, steps, steps.size(), 0, steps.size() * MS, flows);
    }
}
