package com.pcapplayer.persistence.filesystem;

import com.pcapplayer.persistence.BlobStore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Stores objects as files under a directory. For local development without an S3 server. */
public class FileSystemBlobStore implements BlobStore {

    private final Path root;

    public FileSystemBlobStore(Path root) throws IOException {
        this.root = Files.createDirectories(root);
    }

    private Path resolve(String key) {
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) throw new IllegalArgumentException("Bad key " + key);
        return resolved;
    }

    @Override
    public void put(String key, byte[] data, String contentType) throws IOException {
        Path file = resolve(key);
        Files.createDirectories(file.getParent());
        Path tempFile = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tempFile, data);
        Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    public void putFile(String key, Path file, String contentType) throws IOException {
        Path stored = resolve(key);
        Files.createDirectories(stored.getParent());
        Files.copy(file, stored, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public byte[] get(String key) throws IOException {
        Path file = resolve(key);
        return Files.exists(file) ? Files.readAllBytes(file) : null;
    }

    @Override
    public InputStream open(String key) throws IOException {
        return Files.newInputStream(resolve(key));
    }

    @Override
    public void download(String key, Path target) throws IOException {
        Files.copy(resolve(key), target, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void delete(String key) throws IOException {
        Files.deleteIfExists(resolve(key));
    }

    @Override
    public List<String> list(String prefix) throws IOException {
        List<String> out = new ArrayList<>();

        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile).forEach(file -> {
                String key = root.relativize(file).toString().replace('\\', '/');
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
