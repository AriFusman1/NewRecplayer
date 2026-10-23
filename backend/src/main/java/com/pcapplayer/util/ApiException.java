package com.pcapplayer.util;

import java.util.Map;

/**
 * An error to return to the UI. {@code code} is translated client-side; params fill placeholders.
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;
    private final Map<String, Object> params;

    public ApiException(int status, String code, String message) {
        this(status, code, Map.of(), message);
    }

    public ApiException(int status, String code, Map<String, Object> params, String message) {
        super(message);
        this.status = status;
        this.code = code;
        this.params = params;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> params() {
        return params;
    }

    public static ApiException notFound(String what) {
        return new ApiException(404, "NOT_FOUND", Map.of("what", what), what + " not found");
    }

    public static ApiException bad(String code, String message) {
        return new ApiException(400, code, message);
    }
}
