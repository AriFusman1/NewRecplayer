package com.pcapplayer.playback;

import com.pcapplayer.playback.transport.TcpKeepTryingTransport;

import java.util.Map;

/** Writes a keep-trying TCP replay's connection changes to its session log. */
final class ConnectionLog implements TcpKeepTryingTransport.Events {

    private final ItemPlayer.Log log;
    private final String target;
    private final long retrySeconds;

    ConnectionLog(ItemPlayer.Log log, String target, long retrySeconds) {
        this.log = log;
        this.target = target;
        this.retrySeconds = retrySeconds;
    }

    @Override
    public void connected() {
        log.log("info", "LOG_CONNECTED", Map.of());
    }

    @Override
    public void notConnected(String reason, boolean wasConnected) {
        log.log("warning", wasConnected ? "LOG_CONNECTION_LOST_RETRYING" : "LOG_NOT_CONNECTED_RETRYING",
                Map.of("target", target, "reason", reason, "seconds", retrySeconds));
    }
}
