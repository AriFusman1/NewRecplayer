package com.pcapplayer.recordManagement.service;

import com.pcapplayer.pcapProcessing.extract.CachingExtractor;
import com.pcapplayer.pcapProcessing.extract.ExtractionFilter;
import com.pcapplayer.pcapProcessing.extract.ExtractionResult;
import com.pcapplayer.pcapProcessing.script.ReplayScript;

import java.nio.file.Path;
import java.util.List;

/** Answers every extraction with {@code messageCount} messages, without reading the file. */
final class FixedScriptParser implements CachingExtractor.Parser {

    private final long messageCount;

    FixedScriptParser(long messageCount) {
        this.messageCount = messageCount;
    }

    @Override
    public ExtractionResult extract(Path file, ExtractionFilter filter) {
        ReplayScript script = new ReplayScript(filter.mode(), List.of(), messageCount, messageCount * 10, 1_000_000_000L);
        return new ExtractionResult(script, 0, 0, 1, 0, List.of());
    }
}
