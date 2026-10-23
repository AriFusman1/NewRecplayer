package com.pcapplayer.extract;

import java.util.Map;

/**
 * A problem worth showing the user. {@code code} is translated by the UI; params fill its placeholders.
 * Codes: TRUNCATED_PACKETS, UDP_DUPLICATES, INCOMPLETE_FRAGMENTS, TCP_GAPS, OVERLAPPING_CONNECTIONS,
 * CAPTURE_ENDED_ABRUPTLY.
 */
public record Warning(String code, Map<String, Object> params) {

    public static Warning of(String code, Object... kv) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return new Warning(code, m);
    }
}
