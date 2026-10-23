package com.pcapplayer.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Local copies of stored captures (parsing needs a file). Downloads on demand and evicts the least
 * recently used copies above {@code maxBytes}.
 */
public final class FileCache {

    private final BlobStore store;
    private final Path dir;
    private final long maxBytes;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public FileCache(BlobStore store, Path dir, long maxBytes) throws IOException {
        this.store = store;
        this.dir = Files.createDirectories(dir);
        this.maxBytes = maxBytes;
    }

    /** @return a local path for the object, downloading it if needed */
    public Path get(String objectKey) throws IOException {
        Path p = pathFor(objectKey);
        synchronized (locks.computeIfAbsent(objectKey, k -> new Object())) {
            if (!Files.exists(p)) {
                store.download(objectKey, p);
                evict(p);
            }
        }
        Files.setLastModifiedTime(p, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        return p;
    }

    /** Adopts a just-uploaded file as the cached copy (moves it). */
    public void adopt(String objectKey, Path file) throws IOException {
        Path p = pathFor(objectKey);
        Files.move(file, p, StandardCopyOption.REPLACE_EXISTING);
        evict(p);
    }

    public void forget(String objectKey) throws IOException {
        Files.deleteIfExists(pathFor(objectKey));
    }

    private Path pathFor(String objectKey) {
        return dir.resolve(objectKey.replace('/', '_'));
    }

    private void evict(Path keep) throws IOException {
        List<Path> all = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isRegularFile).forEach(all::add);
        }
        long total = 0;
        for (Path p : all) total += Files.size(p);
        if (total <= maxBytes) return;
        all.sort(Comparator.comparingLong(p -> p.toFile().lastModified()));
        for (Path p : all) {
            if (total <= maxBytes) break;
            if (p.equals(keep)) continue;
            total -= Files.size(p);
            Files.deleteIfExists(p); // an open reader keeps working on POSIX
        }
    }
}
