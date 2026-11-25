package com.pcapplayer.autotag.archive;

import com.pcapplayer.errorHandling.ApiException;

import java.util.Map;

/**
 * Normalizes keys and prefixes that come from the UI, so they cannot leave the archive's root
 * prefix: no ".." or "." segments, no backslashes, no leading '/'.
 */
public final class ArchivePaths {

    private ArchivePaths() {
    }

    /** "" or a folder ending in '/'. */
    public static String prefix(String prefix) {
        String clean = clean(prefix);
        return clean.isEmpty() || clean.endsWith("/") ? clean : clean + "/";
    }

    /** A non-empty object key. */
    public static String key(String key) {
        String clean = clean(key);
        if (clean.isEmpty() || clean.endsWith("/")) throw invalid(key);
        return clean;
    }

    private static String clean(String path) {
        if (path == null) return "";
        String trimmed = path.trim();
        while (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
        if (trimmed.contains("\\") || trimmed.contains("//")) throw invalid(path);

        for (String segment : trimmed.split("/")) {
            if (segment.equals("..") || segment.equals(".")) throw invalid(path);
        }
        return trimmed;
    }

    private static ApiException invalid(String path) {
        return new ApiException(400, "ARCHIVE_BAD_PATH", Map.of("path", String.valueOf(path)), "Invalid archive path: " + path);
    }
}
