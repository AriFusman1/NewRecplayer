package com.pcapplayer.catalog;

import com.pcapplayer.extract.CaptureSummary;

/** A stored capture file (stored once per SHA-256) and what it contains. */
public record PcapFile(String id, String sha256, String originalName, String extension, long size, String format,
                       String uploadedBy, long uploadedAtMs, CaptureSummary summary) {

    public String objectKey() {
        return "pcaps/" + id + "." + extension;
    }

    public String metaKey() {
        return "pcaps/" + id + ".json";
    }
}
