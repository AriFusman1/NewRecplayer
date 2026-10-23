package com.pcapplayer.replay;

import com.pcapplayer.extract.ConnectionMode;
import com.pcapplayer.extract.Histogram;
import com.pcapplayer.extract.ReplayScript;

/** Resolves records for the engine (implemented by the catalog). */
public interface ScriptSource {

    record RecordInfo(String recordId, String name, String categoryName, ConnectionMode mode) {
    }

    record Prepared(RecordInfo info, long rangeStartUs, ReplayScript script, Histogram histogram) {
    }

    /** @throws ReplayFailure RECORD_NOT_FOUND */
    RecordInfo describe(String recordId);

    /** Loads the pcap and builds the script; may take a while. @throws ReplayFailure PREPARE_FAILED */
    Prepared prepare(String recordId);
}
