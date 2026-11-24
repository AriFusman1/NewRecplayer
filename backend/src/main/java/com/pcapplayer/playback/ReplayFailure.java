package com.pcapplayer.playback;

import com.pcapplayer.errorHandling.ApiException;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.util.Map;
import java.util.Set;

/**
 * A replay item cannot continue, or a replay request is refused. {@code code} is translated by the UI.
 * Codes: CONNECT_FAILED, CONNECTION_LOST, CLIENT_DISCONNECTED, LISTEN_FAILED, SEND_FAILED,
 * TARGET_BUSY, PREPARE_FAILED, RECORD_NOT_FOUND, SESSION_NOT_FOUND, SEEK_NOT_PLAYING, …
 * <p>
 * The HTTP status follows from the code, so replay code never has to pick status numbers.
 */
public class ReplayFailure extends ApiException {

    private static final Set<String> CONFLICT_CODES = Set.of("TARGET_BUSY", "LISTEN_FAILED", "DUPLICATE_TARGET", "ACTIVE_LIMIT");
    private static final Set<String> NOT_FOUND_CODES = Set.of("SESSION_NOT_FOUND", "RECORD_NOT_FOUND");
    /** Requests that can never be valid, whatever is running. */
    private static final Set<String> BAD_REQUEST_CODES = Set.of("GROUP_TOO_SMALL");

    public ReplayFailure(String code, Map<String, Object> params, String message) {
        super(statusFor(code), code, params, message);
    }

    private static int statusFor(String code) {
        if (CONFLICT_CODES.contains(code)) return HttpResponseStatus.CONFLICT.code();
        if (NOT_FOUND_CODES.contains(code)) return HttpResponseStatus.NOT_FOUND.code();
        if (BAD_REQUEST_CODES.contains(code)) return HttpResponseStatus.BAD_REQUEST.code();
        return HttpResponseStatus.UNPROCESSABLE_ENTITY.code();
    }
}
