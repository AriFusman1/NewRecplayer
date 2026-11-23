package com.pcapplayer.pcap.extract;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * Parsing ({@link MessageExtractor}) with an in-memory cache of results, so the same capture +
 * filter is parsed once: the live preview while a record is defined, saving it right after,
 * editing it, and every replay of it.
 * <p>
 * Results are keyed by the capture's SHA-256 (an upload and the stored file it becomes share it)
 * and the filter. The cache is Caffeine: bounded by {@code maxBytes} of script data, and concurrent
 * requests for the same key wait for one parse instead of each running their own. Failures are
 * not cached.
 */
public class CachingExtractor {

    /** Rough per-step overhead (object headers, list slot) on top of the payload bytes. */
    private static final long STEP_OVERHEAD_BYTES = 48;

    /** The real parse, normally {@link MessageExtractor#extract}. */
    @FunctionalInterface
    public interface Parser {
        ExtractionResult extract(Path file, ExtractionFilter filter) throws IOException;
    }

    private record Key(String sha256, ExtractionFilter filter) {
    }

    private final Parser parser;
    private final Cache<Key, ExtractionResult> cache;

    public CachingExtractor(Parser parser, long maxBytes) {
        this.parser = parser;
        this.cache = Caffeine.newBuilder()
                .maximumWeight(maxBytes)
                .weigher((Key key, ExtractionResult result) -> (int) Math.min(Integer.MAX_VALUE, weight(result)))
                .executor(Runnable::run) // evict on the calling thread: no background work, predictable memory
                .build();
    }

    /**
     * @param sha256 content hash of {@code file}; identifies the capture across upload and storage
     */
    public ExtractionResult extract(String sha256, Path file, ExtractionFilter filter) throws IOException {
        try {
            return cache.get(new Key(sha256, filter), key -> {
                try {
                    return parser.extract(file, filter);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static long weight(ExtractionResult result) {
        return result.script().totalBytes() + (long) result.script().steps().size() * STEP_OVERHEAD_BYTES;
    }
}
