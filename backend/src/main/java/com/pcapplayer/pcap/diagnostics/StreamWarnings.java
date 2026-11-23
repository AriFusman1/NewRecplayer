package com.pcapplayer.pcap.diagnostics;

import com.pcapplayer.pcap.decode.PacketStream;

import java.util.List;

/** Warnings about the capture stream itself (not about the selected traffic). */
public final class StreamWarnings {

    private StreamWarnings() {
    }

    public static void add(PacketStream in, List<Warning> out) {
        Warning.addCount(out, "INCOMPLETE_FRAGMENTS", in.incompleteFragmentSets());
        if (in.endedAbruptly()) out.add(Warning.of("CAPTURE_ENDED_ABRUPTLY"));
    }
}
