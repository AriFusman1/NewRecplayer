package com.pcapplayer.pcapProcessing.extract;

import com.pcapplayer.pcapProcessing.script.ReplayScript;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Counts parses; every result carries {@code bytes} of script data. */
final class CountingExtractor implements CachingExtractor.Parser {

    final AtomicInteger calls = new AtomicInteger();
    final long bytes;
    final AtomicReference<CountDownLatch> gate = new AtomicReference<>();
    final AtomicReference<IOException> fail = new AtomicReference<>();

    CountingExtractor(long bytes) {
        this.bytes = bytes;
    }

    @Override
    public ExtractionResult extract(Path file, ExtractionFilter filter) throws IOException {
        calls.incrementAndGet();
        CountDownLatch latch = gate.get();

        if (latch != null) {
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        IOException failure = fail.get();
        if (failure != null) throw failure;
        ReplayScript script = new ReplayScript(filter.mode(), List.of(), 0, bytes, 0);
        return new ExtractionResult(script, 0, 0, 0, 0, List.of());
    }
}
