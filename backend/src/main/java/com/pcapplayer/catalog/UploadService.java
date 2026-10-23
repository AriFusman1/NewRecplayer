package com.pcapplayer.catalog;

import com.pcapplayer.capture.InvalidCaptureException;
import com.pcapplayer.extract.CaptureScanner;
import com.pcapplayer.extract.CaptureSummary;
import com.pcapplayer.util.ApiException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Files that were uploaded and scanned but not yet saved as a record. They live under the work
 * directory and expire after a while.
 */
public final class UploadService {

    /** An uploaded file waiting to become a record. */
    public record Upload(String id, Path path, String originalName, long size, String sha256,
                         CaptureSummary summary, String uploadedBy, long createdAtMs) {

        public String extension() {
            return "pcapng".equals(summary.format()) ? "pcapng" : "pcap";
        }
    }

    private final Path dir;
    private final CaptureScanner scanner;
    private final long expireMs;
    private final Map<String, Upload> uploads = new ConcurrentHashMap<>();

    public UploadService(Path dir, CaptureScanner scanner, long expireMs) throws IOException {
        this.dir = Files.createDirectories(dir);
        this.scanner = scanner;
        this.expireMs = expireMs;
        try (var s = Files.list(this.dir)) { // leftovers from a previous run
            s.forEach(p -> p.toFile().delete());
        }
    }

    /** A fresh temp path for an incoming upload. */
    public Path newTempPath() {
        return dir.resolve(UUID.randomUUID() + ".upload");
    }

    /** Hashes and scans a fully received temp file. Deletes it if it is not a valid capture. */
    public Upload register(Path temp, String originalName, String uploadedBy) {
        try {
            String sha = sha256(temp);
            CaptureSummary summary;
            try {
                summary = scanner.scan(temp);
            } catch (InvalidCaptureException e) {
                throw new ApiException(422, "INVALID_FILE", Map.of("reason", e.getMessage()), "Invalid pcap file: " + e.getMessage());
            }
            Upload u = new Upload(UUID.randomUUID().toString(), temp, originalName, Files.size(temp), sha, summary,
                    uploadedBy, System.currentTimeMillis());
            uploads.put(u.id(), u);
            return u;
        } catch (IOException e) {
            discardQuietly(temp);
            throw new ApiException(500, "UPLOAD_FAILED", Map.of("reason", String.valueOf(e.getMessage())), "Upload failed");
        } catch (RuntimeException e) {
            discardQuietly(temp);
            throw e;
        }
    }

    public Upload get(String id) {
        Upload u = uploads.get(id);
        if (u == null) throw new ApiException(404, "UPLOAD_EXPIRED", "The uploaded file is no longer available; upload it again");
        return u;
    }

    /** Removes the upload from the registry; the caller owns the file afterwards. */
    public void consume(String id) {
        uploads.remove(id);
    }

    public void discard(String id) {
        Upload u = uploads.remove(id);
        if (u != null) discardQuietly(u.path());
    }

    public void purgeExpired() {
        long now = System.currentTimeMillis();
        uploads.values().removeIf(u -> {
            if (now - u.createdAtMs() <= expireMs) return false;
            discardQuietly(u.path());
            return true;
        });
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), md)) {
            byte[] buf = new byte[1 << 20];
            while (in.read(buf) >= 0) {
                // digesting
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static void discardQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // temp file
        }
    }
}
