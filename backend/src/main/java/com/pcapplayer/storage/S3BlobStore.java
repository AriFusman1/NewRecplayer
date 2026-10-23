package com.pcapplayer.storage;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** AWS S3 or any S3-compatible server (MinIO, Ceph RGW, …). */
public final class S3BlobStore implements BlobStore {

    /** Files above this size are uploaded in parts (single PUT is limited to 5 GB). */
    private static final long MULTIPART_THRESHOLD = 64L * 1024 * 1024;
    private static final int PART_SIZE = 16 * 1024 * 1024;

    public record Settings(String endpoint, String region, String bucket, String accessKey, String secretKey,
                           boolean pathStyle, String prefix) {
    }

    private final S3Client s3;
    private final String bucket;
    private final String prefix;

    public S3BlobStore(Settings cfg) {
        AwsCredentialsProvider creds = cfg.accessKey() == null || cfg.accessKey().isBlank()
                ? DefaultCredentialsProvider.create()
                : StaticCredentialsProvider.create(AwsBasicCredentials.create(cfg.accessKey(), cfg.secretKey()));
        var b = S3Client.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .region(Region.of(cfg.region() == null || cfg.region().isBlank() ? "us-east-1" : cfg.region()))
                .credentialsProvider(creds)
                // SDK ≥ 2.30 defaults to aws-chunked uploads with flexible checksums, which many
                // S3-compatible servers (older MinIO, Ceph, …) store verbatim. Only checksum when required.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(cfg.pathStyle()).build());
        if (cfg.endpoint() != null && !cfg.endpoint().isBlank()) b.endpointOverride(URI.create(cfg.endpoint()));
        this.s3 = b.build();
        this.bucket = cfg.bucket();
        String p = cfg.prefix() == null ? "" : cfg.prefix().trim();
        this.prefix = p.isEmpty() || p.endsWith("/") ? p : p + "/";
    }

    private String k(String key) {
        return prefix + key;
    }

    @Override
    public void put(String key, byte[] data, String contentType) throws IOException {
        try {
            s3.putObject(r -> r.bucket(bucket).key(k(key)).contentType(contentType), RequestBody.fromBytes(data));
        } catch (S3Exception e) {
            throw io(e);
        }
    }

    @Override
    public void putFile(String key, Path file, String contentType) throws IOException {
        long size = Files.size(file);
        try {
            if (size <= MULTIPART_THRESHOLD) {
                s3.putObject(r -> r.bucket(bucket).key(k(key)).contentType(contentType), RequestBody.fromFile(file));
                return;
            }
            String uploadId = s3.createMultipartUpload(r -> r.bucket(bucket).key(k(key)).contentType(contentType)).uploadId();
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                List<CompletedPart> parts = new ArrayList<>();
                byte[] buf = new byte[PART_SIZE];
                int partNo = 1;
                for (long off = 0; off < size; off += PART_SIZE, partNo++) {
                    int len = (int) Math.min(PART_SIZE, size - off);
                    raf.readFully(buf, 0, len);
                    int n = partNo;
                    String etag = s3.uploadPart(r -> r.bucket(bucket).key(k(key)).uploadId(uploadId).partNumber(n),
                            RequestBody.fromBytes(len == PART_SIZE ? buf : java.util.Arrays.copyOf(buf, len))).eTag();
                    parts.add(CompletedPart.builder().partNumber(n).eTag(etag).build());
                }
                s3.completeMultipartUpload(r -> r.bucket(bucket).key(k(key)).uploadId(uploadId)
                        .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build()));
            } catch (IOException | RuntimeException e) {
                s3.abortMultipartUpload(r -> r.bucket(bucket).key(k(key)).uploadId(uploadId));
                throw e;
            }
        } catch (S3Exception e) {
            throw io(e);
        }
    }

    @Override
    public byte[] get(String key) throws IOException {
        try {
            return s3.getObjectAsBytes(r -> r.bucket(bucket).key(k(key))).asByteArray();
        } catch (NoSuchKeyException e) {
            return null;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return null;
            throw io(e);
        }
    }

    @Override
    public InputStream open(String key) throws IOException {
        try {
            return s3.getObject(r -> r.bucket(bucket).key(k(key)));
        } catch (NoSuchKeyException e) {
            throw new FileNotFoundException(key);
        } catch (S3Exception e) {
            throw io(e);
        }
    }

    @Override
    public void download(String key, Path target) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        try {
            Files.deleteIfExists(tmp);
            s3.getObject(r -> r.bucket(bucket).key(k(key)), ResponseTransformer.toFile(tmp));
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (NoSuchKeyException e) {
            throw new FileNotFoundException(key);
        } catch (S3Exception e) {
            throw io(e);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Override
    public void delete(String key) throws IOException {
        try {
            s3.deleteObject(r -> r.bucket(bucket).key(k(key)));
        } catch (S3Exception e) {
            throw io(e);
        }
    }

    @Override
    public List<String> list(String keyPrefix) throws IOException {
        List<String> out = new ArrayList<>();
        try {
            String token = null;
            do {
                ListObjectsV2Request.Builder req = ListObjectsV2Request.builder().bucket(bucket).prefix(k(keyPrefix));
                if (token != null) req.continuationToken(token);
                ListObjectsV2Response resp = s3.listObjectsV2(req.build());
                for (S3Object o : resp.contents()) out.add(o.key().substring(prefix.length()));
                token = Boolean.TRUE.equals(resp.isTruncated()) ? resp.nextContinuationToken() : null;
            } while (token != null);
        } catch (S3Exception e) {
            throw io(e);
        }
        return out;
    }

    @Override
    public void ping() throws IOException {
        try {
            s3.headBucket(r -> r.bucket(bucket));
        } catch (S3Exception e) {
            throw io(e);
        }
    }

    private static IOException io(S3Exception e) {
        return new IOException("S3 error " + e.statusCode() + ": " + e.awsErrorDetails().errorMessage(), e);
    }
}
