package com.pcapplayer.uploadHandling;

import com.pcapplayer.errorHandling.ApiException;
import com.pcapplayer.pcapProcessing.capture.InvalidCaptureException;
import com.pcapplayer.pcapProcessing.capture.OpenedPcap;
import com.pcapplayer.pcapProcessing.capture.PcapInputOpener;
import com.pcapplayer.pcapProcessing.summary.CaptureScanner;
import com.pcapplayer.pcapProcessing.summary.CaptureSummary;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Files that were uploaded and scanned but not yet saved as a record. They live under the work
 * directory and expire after a while.
 */
public class UploadService {

    /** An uploaded file waiting to become a record. */
    public record Upload(String id, Path path, String originalName, long size, String sha256,
                         CaptureSummary summary, String uploadedBy, long createdAtMs) {

        public String extension() {
            return "pcapng".equals(summary.format()) ? "pcapng" : "pcap";
        }
    }

    private static final int HASH_BUFFER_BYTES = 1 << 20;

    private final Path dir;
    private final CaptureScanner scanner;
    private final long expireMs;
    private final Map<String, Upload> uploads = new ConcurrentHashMap<>();

    /** Hashes uploads while the scanner reads them. */
    private final ExecutorService hasher = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "upload-hash");
        thread.setDaemon(true);
        return thread;
    });

    public UploadService(Path dir, CaptureScanner scanner, long expireMs) throws IOException {
        this.dir = Files.createDirectories(dir);
        this.scanner = scanner;
        this.expireMs = expireMs;
        try (Stream<Path> leftovers = Files.list(this.dir)) { // leftovers from a previous run
            leftovers.forEach(UploadService::discardQuietly);
        }
    }

    /** A fresh temp path for an incoming upload. */
    public Path newTempPath() {
        return dir.resolve(UUID.randomUUID() + ".upload");
    }

    /**
     * Hashes and scans a fully received temp file; both read the whole file, so they run at the same
     * time. Deletes the file if it is not a valid capture. A gzipped file (detected by its bytes) is
     * decompressed first and its name loses the ".gz", so "a.pcap.gz" is stored as the capture "a.pcap".
     */
    public Upload register(Path received, String originalName, String uploadedBy) {
        boolean gzipped;
        Path temp;

        try {
            gzipped = PcapInputOpener.isGzipped(received);
            temp = gzipped ? ungzip(received) : received;
        } catch (IOException e) {
            discardQuietly(received);
            throw ApiException.unprocessable("INVALID_FILE", ApiException.reason(e), "Invalid pcap file: " + e.getMessage());
        }
        return registerPlain(temp, PcapInputOpener.cleanName(originalName, gzipped), uploadedBy);
    }

    /**
     * Registers a capture that was already decompressed, hashed and scanned (auto-tagging does that
     * itself). The registry takes ownership of the file.
     */
    public Upload registerScanned(Path pcap, String name, long size, String sha256, CaptureSummary summary, String uploadedBy) {
        Upload upload = new Upload(UUID.randomUUID().toString(), pcap, name, size, sha256, summary, uploadedBy,
                System.currentTimeMillis());
        uploads.put(upload.id(), upload);
        return upload;
    }

    /** Decompresses a gzipped upload into a new temp file and deletes the compressed one. */
    private Path ungzip(Path received) throws IOException {
        Path plain = newTempPath();

        try {
            OpenedPcap opened = PcapInputOpener.open(received, null, plain);
            if (!opened.isPcap()) throw new InvalidCaptureException("The gzipped file does not contain a pcap or pcapng capture");
            return plain;
        } finally {
            discardQuietly(received);
        }
    }

    private Upload registerPlain(Path temp, String originalName, String uploadedBy) {
        CompletableFuture<String> hashing = CompletableFuture.supplyAsync(() -> {
            try {
                return sha256(temp);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, hasher);

        try {
            CaptureSummary summary;

            try {
                summary = scanner.scan(temp);
            } catch (InvalidCaptureException e) {
                throw ApiException.unprocessable("INVALID_FILE", ApiException.reason(e), "Invalid pcap file: " + e.getMessage());
            }
            String sha256 = awaitHash(hashing);
            Upload upload = new Upload(UUID.randomUUID().toString(), temp, originalName, Files.size(temp), sha256, summary,
                    uploadedBy, System.currentTimeMillis());
            uploads.put(upload.id(), upload);
            return upload;
        } catch (IOException e) {
            discardAfter(hashing, temp);
            throw ApiException.internal("UPLOAD_FAILED", ApiException.reason(e), "Upload failed");
        } catch (RuntimeException e) {
            discardAfter(hashing, temp);
            throw e;
        }
    }

    /** Deletes a rejected upload, but only once the hasher has stopped reading it. */
    private static void discardAfter(CompletableFuture<String> hashing, Path temp) {
        hashing.handle((sha, error) -> null).join(); // waits; ignores the hash result or its failure
        discardQuietly(temp);
    }

    private static String awaitHash(CompletableFuture<String> hashing) throws IOException {
        try {
            return hashing.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof UncheckedIOException ioError) throw ioError.getCause();
            throw e;
        }
    }

    public Upload get(String id) {
        Upload upload = uploads.get(id);
        if (upload == null) throw ApiException.notFound("UPLOAD_EXPIRED", Map.of(), "The uploaded file is no longer available; upload it again");
        return upload;
    }

    /** Removes the upload from the registry; the caller owns the file afterwards. */
    public void consume(String id) {
        uploads.remove(id);
    }

    public void discard(String id) {
        Upload upload = uploads.remove(id);
        if (upload != null) discardQuietly(upload.path());
    }

    public void purgeExpired() {
        long now = System.currentTimeMillis();

        uploads.values().removeIf(upload -> {
            if (now - upload.createdAtMs() <= expireMs) return false;
            discardQuietly(upload.path());
            return true;
        });
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;

        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }

        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            byte[] buffer = new byte[HASH_BUFFER_BYTES];

            while (in.read(buffer) >= 0) {
                // digesting
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void discardQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // temp file
        }
    }
}
