package com.pcapplayer.pcapProcessing.extract;

import com.pcapplayer.pcapProcessing.diagnostics.Warning;
import com.pcapplayer.pcapProcessing.flow.TcpStreamReassembler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** TCP_GAPS totals, with details for the first {@link #MAX_DETAILS} gaps. */
final class TcpGapReport {

    private static final int MAX_DETAILS = 20;

    private long count;
    private long bytes;
    private final List<Map<String, Object>> details = new ArrayList<>();

    void add(TcpStreamReassembler.Gap gap, TcpConnection.FlowKey connection) {
        count++;
        bytes += gap.bytes();

        if (details.size() < MAX_DETAILS) {
            details.add(Map.of("tsUs", TimeUnit.NANOSECONDS.toMicros(gap.tsNanos()), "bytes", gap.bytes(),
                    "connection", connection.describe()));
        }
    }

    void addTo(List<Warning> warnings) {
        if (count > 0) warnings.add(Warning.of("TCP_GAPS", "count", count, "bytes", bytes, "details", details));
    }
}
