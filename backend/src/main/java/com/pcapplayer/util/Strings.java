package com.pcapplayer.util;

/** Null-safe string helpers for request fields. */
public final class Strings {

    private Strings() {
    }

    public static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    /** Trimmed value, or null when missing or blank. */
    public static String blankToNull(String text) {
        return isBlank(text) ? null : text.trim();
    }

    /** Trimmed value, or "" when missing. */
    public static String trimToEmpty(String text) {
        return text == null ? "" : text.trim();
    }

    /** The value as given, or {@code fallback} when missing or blank. */
    public static String orDefault(String text, String fallback) {
        return isBlank(text) ? fallback : text;
    }
}
