package com.pcapplayer.pcapProcessing.diagnostics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A problem worth showing the user. {@code code} is translated by the UI; params fill its placeholders.
 * Codes: TRUNCATED_PACKETS, UDP_DUPLICATES, INCOMPLETE_FRAGMENTS, TCP_GAPS, OVERLAPPING_CONNECTIONS,
 * CAPTURE_ENDED_ABRUPTLY.
 */
public record Warning(String code, Map<String, Object> params) {

    public static Warning of(String code, Object... kv) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) params.put((String) kv[i], kv[i + 1]);
        return new Warning(code, params);
    }

    /** Adds {@code code} with a {@code count} param, unless the count is zero. */
    public static void addCount(List<Warning> out, String code, long count) {
        if (count > 0) out.add(of(code, "count", count));
    }
}
