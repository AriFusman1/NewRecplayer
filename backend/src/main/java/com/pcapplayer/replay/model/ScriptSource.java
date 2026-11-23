package com.pcapplayer.replay.model;

import com.pcapplayer.pcap.script.EConnectionMode;
import com.pcapplayer.pcap.script.Histogram;
import com.pcapplayer.pcap.script.ReplayScript;

/** Resolves records for the engine (implemented by the catalog). */
public interface ScriptSource {

    record RecordInfo(String recordId, String name, String categoryName, EConnectionMode mode) {
    }

    record Prepared(RecordInfo info, long rangeStartUs, ReplayScript script, Histogram histogram) {
    }

    /** @throws ReplayFailure RECORD_NOT_FOUND */
    RecordInfo describe(String recordId);

    /** Loads the pcap and builds the script; may take a while. @throws ReplayFailure PREPARE_FAILED */
    Prepared prepare(String recordId);
}
