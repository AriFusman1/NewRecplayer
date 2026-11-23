package com.pcapplayer.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * Local copies of stored captures (parsing needs a file). Downloads on demand and evicts the least
 * recently used copies above {@code maxBytes}.
 */
public class FileCache {

    private final BlobStore store;
    private final Path dir;
    private final long maxBytes;
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public FileCache(BlobStore store, Path dir, long maxBytes) throws IOException {
        this.store = store;
        this.dir = Files.createDirectories(dir);
        this.maxBytes = maxBytes;
    }

    /** @return a local path for the object, downloading it if needed */
    public Path get(String objectKey) throws IOException {
        Path cached = pathFor(objectKey);
        ReentrantLock lock = locks.computeIfAbsent(objectKey, newKey -> new ReentrantLock());
        lock.lock();

        try {
            if (!Files.exists(cached)) {
                store.download(objectKey, cached);
                evict(cached);
            }
        } finally {
            lock.unlock();
        }
        Files.setLastModifiedTime(cached, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        return cached;
    }

    /** Adopts a just-uploaded file as the cached copy (moves it). */
    public void adopt(String objectKey, Path file) throws IOException {
        Path cached = pathFor(objectKey);
        Files.move(file, cached, StandardCopyOption.REPLACE_EXISTING);
        evict(cached);
    }

    public void forget(String objectKey) throws IOException {
        Files.deleteIfExists(pathFor(objectKey));
    }

    private Path pathFor(String objectKey) {
        return dir.resolve(objectKey.replace('/', '_'));
    }

    private void evict(Path keep) throws IOException {
        List<Path> all = new ArrayList<>();

        try (Stream<Path> listing = Files.list(dir)) {
            listing.filter(Files::isRegularFile).forEach(all::add);
        }
        long total = 0;
        for (Path file : all) total += Files.size(file);
        if (total <= maxBytes) return;
        all.sort(Comparator.comparingLong(file -> file.toFile().lastModified()));

        for (Path file : all) {
            if (total <= maxBytes) break;
            if (file.equals(keep)) continue;
            total -= Files.size(file);
            Files.deleteIfExists(file); // an open reader keeps working on POSIX
        }
    }
}
