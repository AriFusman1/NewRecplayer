package com.pcapplayer.playback.model;

import com.pcapplayer.pcapProcessing.script.EConnectionMode;
import com.pcapplayer.pcapProcessing.script.Histogram;
import com.pcapplayer.pcapProcessing.script.ReplayScript;

/** Resolves records for the engine (implemented by the catalog). */
public interface ScriptSource {

    record RecordInfo(String recordId, String name, String categoryName, EConnectionMode mode) {
    }

    /** @param timeFix the time fields to rewrite while playing; null to send the bytes as recorded */
    record Prepared(RecordInfo info, long rangeStartUs, ReplayScript script, Histogram histogram, TimeFix timeFix) {
    }

    /** @throws ReplayFailure RECORD_NOT_FOUND */
    RecordInfo describe(String recordId);

    /** Loads the pcap and builds the script; may take a while. @throws ReplayFailure PREPARE_FAILED */
    Prepared prepare(String recordId);
}
