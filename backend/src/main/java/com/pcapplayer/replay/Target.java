package com.pcapplayer.replay;

import com.pcapplayer.extract.ConnectionMode;

/**
 * Where an item is played: {@code host:port} for TCP_SERVER / UDP_SERVER, listen {@code port} for TCP_CLIENT
 * (host is then null).
 */
public record Target(String host, int port) {

    public String describe(ConnectionMode mode) {
        return mode == ConnectionMode.TCP_CLIENT ? "listen :" + port : host + ":" + port;
    }
}
