package com.pcapplayer.replay.model;

import com.pcapplayer.pcap.script.EConnectionMode;

/**
 * Where an item is played: {@code host:port} for TCP_SERVER / UDP_SERVER, listen {@code port} for TCP_CLIENT
 * (host is then null).
 */
public record Target(String host, int port) {

    public String describe(EConnectionMode mode) {
        return mode == EConnectionMode.TCP_CLIENT ? "listen :" + port : host + ":" + port;
    }
}
