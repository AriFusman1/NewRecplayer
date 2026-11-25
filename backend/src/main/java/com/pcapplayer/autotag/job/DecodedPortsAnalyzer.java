package com.pcapplayer.autotag.job;

import com.pcapplayer.icd.analysis.MessageAnalyzer;
import com.pcapplayer.icd.decode.DecodedMessage;
import com.pcapplayer.pcapProcessing.script.EConnectionMode;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** The service-side ports of the flows where at least one ICD message was decoded: the saved record's ports. */
final class DecodedPortsAnalyzer implements MessageAnalyzer {

    private final EConnectionMode mode;
    private final Set<Integer> ports = new TreeSet<>();

    DecodedPortsAnalyzer(EConnectionMode mode) {
        this.mode = mode;
    }

    @Override
    public void onMessage(DecodedMessage message) {
        if (message.flow() == null) return;
        ports.add(mode.filterOnDestination() ? message.flow().dstPort() : message.flow().srcPort());
    }

    List<Integer> ports() {
        return List.copyOf(ports);
    }
}
