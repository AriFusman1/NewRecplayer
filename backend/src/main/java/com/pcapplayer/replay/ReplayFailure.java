package com.pcapplayer.replay;

import java.util.Map;

/**
 * A replay item cannot continue. {@code code} is translated by the UI.
 * Codes: CONNECT_FAILED, CONNECTION_LOST, CLIENT_DISCONNECTED, LISTEN_FAILED, SEND_FAILED,
 * TARGET_BUSY, PREPARE_FAILED.
 */
public class ReplayFailure extends RuntimeException {

    private final String code;
    private final Map<String, Object> params;

    public ReplayFailure(String code, Map<String, Object> params, String message) {
        super(message);
        this.code = code;
        this.params = params;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> params() {
        return params;
    }
}
