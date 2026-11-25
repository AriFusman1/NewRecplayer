package com.pcapplayer.autotag.archive;

/**
 * One object in the archive.
 *
 * @param key            relative to the archive's root prefix, e.g. "2026/11/site-a/capture.pcap.gz"
 * @param lastModifiedUs S3 LastModified, epoch microseconds (UTC)
 */
public record ArchiveObject(String key, long size, long lastModifiedUs) {

    /** The part after the last '/'. */
    public String fileName() {
        return key.substring(key.lastIndexOf('/') + 1);
    }
}
