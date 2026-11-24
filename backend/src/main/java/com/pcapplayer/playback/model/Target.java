package com.pcapplayer.playback.model;

import com.pcapplayer.pcapProcessing.script.EConnectionMode;

/**
 * Where an item is played: {@code host:port} for TCP_SERVER / UDP_SERVER, listen {@code port} for TCP_CLIENT
 * (host is then null).
 */
public record Target(String host, int port) {

    public String describe(EConnectionMode mode) {
        return mode == EConnectionMode.TCP_CLIENT ? "listen :" + port : host + ":" + port;
    }
}
