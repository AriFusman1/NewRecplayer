package com.pcapplayer.pcapProcessing.extract;

import com.pcapplayer.pcapProcessing.script.EConnectionMode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CachingExtractorTest {

    private static final Path FILE = Path.of("unused.pcap");

    private static ExtractionFilter filter(int port) {
        return new ExtractionFilter(EConnectionMode.UDP_SERVER, Set.of(port), null, 0, 1);
    }

    @Test
    void sameCaptureAndFilterIsParsedOnce() throws IOException {
        CountingExtractor parser = new CountingExtractor(10);
        CachingExtractor cache = new CachingExtractor(parser, 1_000);
        ExtractionResult first = cache.extract("sha", FILE, filter(1));
        ExtractionResult second = cache.extract("sha", Path.of("other/path/same/content.pcap"), filter(1));
        assertSame(first, second);
        assertEquals(1, parser.calls.get());
        cache.extract("sha", FILE, filter(2));     // different filter
        cache.extract("sha-2", FILE, filter(1));   // different capture
        assertEquals(3, parser.calls.get());
    }

    @Test
    void staysWithinTheMemoryLimit() throws IOException {
        CountingExtractor parser = new CountingExtractor(400);
        CachingExtractor cache = new CachingExtractor(parser, 1_000);
        for (String sha : new String[]{"a", "b", "c"}) cache.extract(sha, FILE, filter(1));
        assertEquals(3, parser.calls.get());
        // 3 x 400 > 1000, so at most two results are still cached: asking again for all three re-parses at least one
        for (String sha : new String[]{"a", "b", "c"}) cache.extract(sha, FILE, filter(1));
        assertTrue(parser.calls.get() >= 4, "parses: " + parser.calls.get());
    }

    @Test
    void zeroDisablesTheCache() throws IOException {
        CountingExtractor parser = new CountingExtractor(10);
        CachingExtractor cache = new CachingExtractor(parser, 0);
        cache.extract("sha", FILE, filter(1));
        cache.extract("sha", FILE, filter(1));
        assertEquals(2, parser.calls.get());
    }

    @Test
    void resultLargerThanTheCacheIsNotKept() throws IOException {
        CountingExtractor parser = new CountingExtractor(5_000);
        CachingExtractor cache = new CachingExtractor(parser, 1_000);
        cache.extract("big", FILE, filter(1));
        cache.extract("big", FILE, filter(1));
        assertEquals(2, parser.calls.get());
    }

    @Test
    void concurrentRequestsShareOneParse() throws Exception {
        CountingExtractor parser = new CountingExtractor(10);
        parser.gate.set(new CountDownLatch(1));
        CachingExtractor cache = new CachingExtractor(parser, 1_000);
        ExecutorService pool = Executors.newFixedThreadPool(4);

        try {
            List<Future<ExtractionResult>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) futures.add(pool.submit(() -> cache.extract("sha", FILE, filter(1))));
            Thread.sleep(100); // let all four reach the cache
            parser.gate.get().countDown();
            ExtractionResult first = futures.get(0).get();
            for (Future<ExtractionResult> future : futures) assertSame(first, future.get());
            assertEquals(1, parser.calls.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void failuresAreNotCached() throws IOException {
        CountingExtractor parser = new CountingExtractor(10);
        parser.fail.set(new IOException("broken"));
        CachingExtractor cache = new CachingExtractor(parser, 1_000);
        assertThrows(IOException.class, () -> cache.extract("sha", FILE, filter(1)));
        parser.fail.set(null);
        cache.extract("sha", FILE, filter(1));
        assertEquals(2, parser.calls.get());
    }
}
