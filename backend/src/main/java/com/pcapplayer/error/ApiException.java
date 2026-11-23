package com.pcapplayer.error;

import io.netty.handler.codec.http.HttpResponseStatus;

import java.util.Map;

/**
 * An error to return to the UI. {@code code} is translated client-side; params fill placeholders.
 * Prefer the named factories over picking status numbers by hand.
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

    // ------------------------------------------------------------------ factories

    /** 400: the request itself is malformed. */
    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpResponseStatus.BAD_REQUEST.code(), code, message);
    }

    /** 404 NOT_FOUND for a missing entity, e.g. {@code notFound("Record")}. */
    public static ApiException notFound(String what) {
        return notFound("NOT_FOUND", Map.of("what", what), what + " not found");
    }

    public static ApiException notFound(String code, Map<String, Object> params, String message) {
        return new ApiException(HttpResponseStatus.NOT_FOUND.code(), code, params, message);
    }

    /** 409: the request clashes with existing state (a duplicate name, a busy target). */
    public static ApiException conflict(String code, Map<String, Object> params, String message) {
        return new ApiException(HttpResponseStatus.CONFLICT.code(), code, params, message);
    }

    /** 422: well-formed, but fails a business rule. */
    public static ApiException unprocessable(String code, String message) {
        return unprocessable(code, Map.of(), message);
    }

    public static ApiException unprocessable(String code, Map<String, Object> params, String message) {
        return new ApiException(HttpResponseStatus.UNPROCESSABLE_ENTITY.code(), code, params, message);
    }

    /** 500 for a known failure that is not the caller's fault. */
    public static ApiException internal(String code, Map<String, Object> params, String message) {
        return new ApiException(HttpResponseStatus.INTERNAL_SERVER_ERROR.code(), code, params, message);
    }

    /** 503: a dependency (storage) or the server itself is not available right now. */
    public static ApiException unavailable(String code, Map<String, Object> params, String message) {
        return new ApiException(HttpResponseStatus.SERVICE_UNAVAILABLE.code(), code, params, message);
    }

    /** {"reason": message} for wrapping a lower-level failure. */
    public static Map<String, Object> reason(Throwable cause) {
        return Map.of("reason", String.valueOf(cause.getMessage()));
    }
}
