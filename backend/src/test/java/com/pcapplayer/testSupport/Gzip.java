package com.pcapplayer.testSupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

/** gzip helpers for tests. */
public final class Gzip {

    private Gzip() {
    }

    public static byte[] gzip(byte[] data) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
            out.write(data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** Writes {@code source} gzipped to {@code target}. */
    public static Path gzipFile(Path source, Path target) throws IOException {
        return Files.write(target, gzip(Files.readAllBytes(source)));
    }
}
