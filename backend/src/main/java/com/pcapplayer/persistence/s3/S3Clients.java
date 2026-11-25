package com.pcapplayer.persistence.s3;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

/** Builds an {@link S3Client} for AWS or any S3-compatible server. Shared by the system store and the archive. */
public final class S3Clients {

    private S3Clients() {
    }

    /**
     * @param endpoint  empty = AWS
     * @param accessKey empty = the AWS default credential chain
     */
    public static S3Client build(String endpoint, String region, String accessKey, String secretKey, boolean pathStyle) {
        AwsCredentialsProvider creds = accessKey == null || accessKey.isBlank()
                ? DefaultCredentialsProvider.create()
                : StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        var builder = S3Client.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .region(Region.of(region == null || region.isBlank() ? "us-east-1" : region))
                .credentialsProvider(creds)
                // SDK ≥ 2.30 defaults to aws-chunked uploads with flexible checksums, which many
                // S3-compatible servers (older MinIO, Ceph, …) store verbatim. Only checksum when required.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build());
        if (endpoint != null && !endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
        return builder.build();
    }
}
