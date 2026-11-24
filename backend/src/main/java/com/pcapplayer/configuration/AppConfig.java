package com.pcapplayer.configuration;

import com.pcapplayer.common.Secrets;

/** Typed view of application.yml (kebab-case keys). */
public record AppConfig(Server server, Storage storage, S3 s3, String workDir, Cache cache, Upload upload,
                        Replay replay, Extraction extraction, History history, HistogramCfg histogram) {

    public record Server(String host, int port) {
    }

    public record Storage(String type, String filesystemRoot) {
    }

    public record S3(String endpoint, String region, String bucket, String accessKey, String secretKey,
                     boolean pathStyle, String prefix) {

        /** Never prints the secret key (records include every field in toString by default). */
        @Override
        public String toString() {
            return "S3[endpoint=" + endpoint + ", region=" + region + ", bucket=" + bucket + ", accessKey=" + accessKey
                    + ", secretKey=" + Secrets.mask(secretKey) + ", pathStyle=" + pathStyle + ", prefix=" + prefix + "]";
        }
    }

    public record Cache(long maxBytes) {
    }

    public record Upload(int expireMinutes) {
    }

    /**
     * @param maxActiveReplays     replays running at the same time, for all users together
     * @param groupConnectTimeoutMs how long records played together wait for their TCP clients to connect
     * @param tcpKeepTrying         TCP-server replays play even with no connection, retrying it in the background
     * @param tcpRetryIntervalMs    how often they retry
     */
    public record Replay(int connectTimeoutMs, int maxActiveReplays, long groupConnectTimeoutMs, boolean tcpKeepTrying,
                         long tcpRetryIntervalMs) {
    }

    public record Extraction(long fragmentTimeoutMs, long tcpGapTimeoutMs, long udpDuplicateWindowMs, long cacheMaxBytes) {
    }

    public record History(int size) {
    }

    public record HistogramCfg(int maxBins) {
    }
}
