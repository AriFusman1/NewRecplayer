package com.pcapplayer.autotag.archive;

import com.pcapplayer.configuration.AppConfig;
import com.pcapplayer.persistence.s3.S3Clients;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The archive on S3 (or an S3-compatible server). Only List, Head and Get requests are ever sent.
 * Credentials stay here; callers only see keys, sizes and dates.
 */
public class S3ArchiveStore implements ArchiveStore {

    private static final String DELIMITER = "/";

    private final S3Client client;
    private final String bucket;
    private final String root;

    public S3ArchiveStore(AppConfig.ArchiveS3 config) {
        this.client = S3Clients.build(config.endpoint(), config.region(), config.accessKey(), config.secretKey(), config.pathStyle());
        this.bucket = config.bucket();
        this.root = ArchivePaths.prefix(config.rootPrefix());
    }

    @Override
    public ArchiveFolder browse(String prefix) throws IOException {
        List<String> folders = new ArrayList<>();
        List<ArchiveObject> files = new ArrayList<>();

        listPages(prefix, false, page -> {
            for (CommonPrefix common : page.commonPrefixes()) folders.add(relative(common.prefix()));
            for (S3Object object : page.contents()) files.add(toObject(object));
        });
        return new ArchiveFolder(prefix, folders, ArchiveSelection.filter(files, null));
    }

    @Override
    public List<ArchiveObject> list(String prefix, boolean recursive) throws IOException {
        List<ArchiveObject> objects = new ArrayList<>();
        listPages(prefix, recursive, page -> {
            for (S3Object object : page.contents()) objects.add(toObject(object));
        });
        return objects;
    }

    @Override
    public ArchiveObject head(String key) throws IOException {
        try {
            HeadObjectResponse head = client.headObject(HeadObjectRequest.builder().bucket(bucket).key(root + key).build());
            return new ArchiveObject(key, head.contentLength(), TimeSpan.micros(head.lastModified()));
        } catch (NoSuchKeyException e) {
            return null;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return null;
            throw new IOException("Archive: " + e.getMessage(), e);
        } catch (SdkException e) {
            throw new IOException("Archive: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream open(String key) throws IOException {
        try {
            return client.getObject(GetObjectRequest.builder().bucket(bucket).key(root + key).build());
        } catch (SdkException e) {
            throw new IOException("Archive: cannot read " + key + ": " + e.getMessage(), e);
        }
    }

    private interface PageHandler {
        void accept(ListObjectsV2Response page);
    }

    private void listPages(String prefix, boolean recursive, PageHandler handler) throws IOException {
        String token = null;

        try {
            do {
                ListObjectsV2Request.Builder request = ListObjectsV2Request.builder().bucket(bucket).prefix(root + prefix);
                if (!recursive) request.delimiter(DELIMITER);
                if (token != null) request.continuationToken(token);
                ListObjectsV2Response page = client.listObjectsV2(request.build());
                handler.accept(page);
                token = page.isTruncated() ? page.nextContinuationToken() : null;
            } while (token != null);
        } catch (SdkException e) {
            throw new IOException("Archive: cannot list " + prefix + ": " + e.getMessage(), e);
        }
    }

    private ArchiveObject toObject(S3Object object) {
        return new ArchiveObject(relative(object.key()), object.size(), TimeSpan.micros(object.lastModified()));
    }

    private String relative(String fullKey) {
        return fullKey.startsWith(root) ? fullKey.substring(root.length()) : fullKey;
    }
}
