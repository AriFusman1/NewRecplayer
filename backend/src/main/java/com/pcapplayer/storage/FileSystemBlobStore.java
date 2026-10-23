package com.pcapplayer.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Stores objects as files under a directory. For local development without an S3 server. */
public final class FileSystemBlobStore implements BlobStore {

    private final Path root;

    public FileSystemBlobStore(Path root) throws IOException {
        this.root = Files.createDirectories(root);
    }

    private Path path(String key) {
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) throw new IllegalArgumentException("Bad key " + key);
        return p;
    }

    @Override
    public void put(String key, byte[] data, String contentType) throws IOException {
        Path p = path(key);
        Files.createDirectories(p.getParent());
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        Files.write(tmp, data);
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    public void putFile(String key, Path file, String contentType) throws IOException {
        Path p = path(key);
        Files.createDirectories(p.getParent());
        Files.copy(file, p, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public byte[] get(String key) throws IOException {
        Path p = path(key);
        return Files.exists(p) ? Files.readAllBytes(p) : null;
    }

    @Override
    public InputStream open(String key) throws IOException {
        return Files.newInputStream(path(key));
    }

    @Override
    public void download(String key, Path target) throws IOException {
        Files.copy(path(key), target, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void delete(String key) throws IOException {
        Files.deleteIfExists(path(key));
    }

    @Override
    public List<String> list(String prefix) throws IOException {
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(Files::isRegularFile).forEach(p -> {
                String key = root.relativize(p).toString().replace('\\', '/');
                if (key.startsWith(prefix) && !key.endsWith(".tmp")) out.add(key);
            });
        }
        return out;
    }

    @Override
    public void ping() {
        // always reachable
    }
}
