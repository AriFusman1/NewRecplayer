package com.pcapplayer.playback.transport;

import com.pcapplayer.playback.Control;
import com.pcapplayer.playback.ReplayFailure;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;

/** UDP-server mode: each message is one datagram to host:port from an ephemeral local port. */
public class UdpSendTransport implements Transport {

    private final String host;
    private final int port;
    private DatagramSocket socket;
    private InetSocketAddress target;

    public UdpSendTransport(String host, int port) {
        this.host = host;
        this.port = port;
    }

    @Override
    public void open(Control control) {
        try {
            target = new InetSocketAddress(InetAddress.getByName(host), port);
            socket = new DatagramSocket();
            control.closeOnAbort(socket);
        } catch (IOException e) {
            throw new ReplayFailure("CONNECT_FAILED", Map.of("target", host + ":" + port, "reason", Sockets.reason(e)),
                    "Cannot send to " + host + ":" + port + ": " + Sockets.reason(e));
        }
    }

    @Override
    public void send(byte[] data, Control control) {
        try {
            socket.send(new DatagramPacket(data, data.length, target));
        } catch (IOException e) {
            control.checkAlive();
            throw new ReplayFailure("SEND_FAILED", Map.of("target", host + ":" + port, "reason", Sockets.reason(e)),
                    "UDP send to " + host + ":" + port + " failed: " + Sockets.reason(e));
        }
    }

    @Override
    public void closeConnection() {
        // connectionless
    }

    @Override
    public void reopen(Control control) {
        // connectionless
    }

    @Override
    public boolean waitsForPeer() {
        return false;
    }

    @Override
    public void close() {
        if (socket != null) socket.close();
    }
}
