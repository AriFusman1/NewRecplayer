package com.pcapplayer.storage.s3;

import com.pcapplayer.storage.BlobStore;
import com.pcapplayer.util.Secrets;
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
import java.util.Arrays;
import java.util.List;

/** AWS S3 or any S3-compatible server (MinIO, Ceph RGW, …). */
public class S3BlobStore implements BlobStore {

    /** Files above this size are uploaded in parts (single PUT is limited to 5 GB). */
    private static final long MULTIPART_THRESHOLD = 64L * 1024 * 1024;
    private static final int PART_SIZE = 16 * 1024 * 1024;

    public record Settings(String endpoint, String region, String bucket, String accessKey, String secretKey,
                           boolean pathStyle, String prefix) {

        /** Never prints the secret key. */
        @Override
        public String toString() {
            return "S3BlobStore.Settings[endpoint=" + endpoint + ", bucket=" + bucket + ", accessKey=" + accessKey
                    + ", secretKey=" + Secrets.mask(secretKey) + ", pathStyle=" + pathStyle + ", prefix=" + prefix + "]";
        }
    }

    private final S3Client client;
    private final String bucket;
    private final String prefix;

    public S3BlobStore(Settings settings) {
        AwsCredentialsProvider creds = settings.accessKey() == null || settings.accessKey().isBlank()
                ? DefaultCredentialsProvider.create()
                : StaticCredentialsProvider.create(AwsBasicCredentials.create(settings.accessKey(), settings.secretKey()));
        var builder = S3Client.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .region(Region.of(settings.region() == null || settings.region().isBlank() ? "us-east-1" : settings.region()))
                .credentialsProvider(creds)
                // SDK ≥ 2.30 defaults to aws-chunked uploads with flexible checksums, which many
                // S3-compatible servers (older MinIO, Ceph, …) store verbatim. Only checksum when required.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(settings.pathStyle()).build());
        if (settings.endpoint() != null && !settings.endpoint().isBlank()) builder.endpointOverride(URI.create(settings.endpoint()));
        this.client = builder.build();
        this.bucket = settings.bucket();
        String trimmedPrefix = settings.prefix() == null ? "" : settings.prefix().trim();
        this.prefix = trimmedPrefix.isEmpty() || trimmedPrefix.endsWith("/") ? trimmedPrefix : trimmedPrefix + "/";
    }

    private String objectKey(String key) {
        return prefix + key;
    }

    @Override
    public void put(String key, byte[] data, String contentType) throws IOException {
        try {
            client.putObject(request -> request.bucket(bucket).key(objectKey(key)).contentType(contentType), RequestBody.fromBytes(data));
        } catch (S3Exception e) {
            throw io(e);
        }
    }

    @Override
    public void putFile(String key, Path file, String contentType) throws IOException {
        long size = Files.size(file);
        try {
            if (size <= MULTIPART_THRESHOLD) {
                client.putObject(request -> request.bucket(bucket).key(objectKey(key)).contentType(contentType), RequestBody.fromFile(file));
                return;
            }
            String uploadId = client.createMultipartUpload(request -> request.bucket(bucket).key(objectKey(key)).contentType(contentType)).uploadId();
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                List<CompletedPart> parts = new ArrayList<>();
                byte[] buffer = new byte[PART_SIZE];
                int partNo = 1;
                for (long offset = 0; offset < size; offset += PART_SIZE, partNo++) {
                    int length = (int) Math.min(PART_SIZE, size - offset);
                    raf.readFully(buffer, 0, length);
                    int partNumber = partNo;
                    String etag = client.uploadPart(request -> request.bucket(bucket).key(objectKey(key)).uploadId(uploadId).partNumber(partNumber),
                            RequestBody.fromBytes(length == PART_SIZE ? buffer : Arrays.copyOf(buffer, length))).eTag();
                    parts.add(CompletedPart.builder().partNumber(partNumber).eTag(etag).build());
                }
                client.completeMultipartUpload(request -> request.bucket(bucket).key(objectKey(key)).uploadId(uploadId)
                        .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build()));
            } catch (IOException | RuntimeException e) {
                client.abortMultipartUpload(request -> request.bucket(bucket).key(objectKey(key)).uploadId(uploadId));
                throw e;
            }
        } catch (S3Exception e) {
            throw io(e);
        }
    }

    @Override
    public byte[] get(String key) throws IOException {
        try {
            return client.getObjectAsBytes(request -> request.bucket(bucket).key(objectKey(key))).asByteArray();
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
            return client.getObject(request -> request.bucket(bucket).key(objectKey(key)));
        } catch (NoSuchKeyException e) {
            throw new FileNotFoundException(key);
        } catch (S3Exception e) {
            throw io(e);
        }
    }

    @Override
    public void download(String key, Path target) throws IOException {
        Path tempFile = target.resolveSibling(target.getFileName() + ".part");
        try {
            Files.deleteIfExists(tempFile);
            client.getObject(request -> request.bucket(bucket).key(objectKey(key)), ResponseTransformer.toFile(tempFile));
            Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (NoSuchKeyException e) {
            throw new FileNotFoundException(key);
        } catch (S3Exception e) {
            throw io(e);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Override
    public void delete(String key) throws IOException {
        try {
            client.deleteObject(request -> request.bucket(bucket).key(objectKey(key)));
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
                ListObjectsV2Request.Builder listRequest = ListObjectsV2Request.builder().bucket(bucket).prefix(objectKey(keyPrefix));
                if (token != null) listRequest.continuationToken(token);
                ListObjectsV2Response page = client.listObjectsV2(listRequest.build());
                for (S3Object object : page.contents()) out.add(object.key().substring(prefix.length()));
                token = Boolean.TRUE.equals(page.isTruncated()) ? page.nextContinuationToken() : null;
            } while (token != null);
        } catch (S3Exception e) {
            throw io(e);
        }
        return out;
    }

    @Override
    public void ping() throws IOException {
        try {
            client.headBucket(request -> request.bucket(bucket));
        } catch (S3Exception e) {
            throw io(e);
        }
    }

    private static IOException io(S3Exception e) {
        return new IOException("S3 error " + e.statusCode() + ": " + e.awsErrorDetails().errorMessage(), e);
    }
}
