package com.pcapplayer.recordManagement.model;

import com.pcapplayer.pcapProcessing.diagnostics.Warning;

import java.util.List;

/** Computed from the pcap + filters + range whenever a record is created or its times change. */
public record RecordStats(String protocol, long messageCount, long bytes, long durationUs, int connections,
                          long firstMessageUs, long lastMessageUs, List<Warning> warnings,
                          long binUs, long[] histogram) {
}
