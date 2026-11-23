package com.pcapplayer.replay.transport;

import com.pcapplayer.replay.Control;
import com.pcapplayer.replay.ReplayFailure;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** TCP-server mode: the replayer is the TCP client and connects to the service. */
public class TcpConnectTransport implements Transport {

    private final String host;
    private final int port;
    private final int connectTimeoutMs;
    private final AtomicReference<Socket> socket = new AtomicReference<>();
    private OutputStream out; // replay thread only
    private final AtomicBoolean expectedClose = new AtomicBoolean();

    public TcpConnectTransport(String host, int port, int connectTimeoutMs) {
        this.host = host;
        this.port = port;
        this.connectTimeoutMs = connectTimeoutMs;
    }

    @Override
    public void open(Control control) {
        Socket connecting = new Socket();
        control.closeOnAbort(connecting);
        try {
            connecting.setTcpNoDelay(true);
            connecting.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            out = connecting.getOutputStream();
        } catch (IOException e) {
            Sockets.closeQuietly(connecting);
            control.checkAlive();
            throw new ReplayFailure("CONNECT_FAILED", Map.of("target", host + ":" + port, "reason", Sockets.reason(e)),
                    "Connect to " + host + ":" + port + " failed: " + Sockets.reason(e));
        }
        expectedClose.set(false);
        socket.set(connecting);
        Drainer.start(connecting, "drain-" + host + ":" + port, reason -> {
            if (!expectedClose.get() && socket.get() == connecting) {
                control.fail(new ReplayFailure("CONNECTION_LOST", Map.of("target", host + ":" + port, "reason", reason),
                        "Connection to " + host + ":" + port + " dropped: " + reason));
            }
        });
    }

    @Override
    public void send(byte[] data, Control control) {
        try {
            out.write(data);
            out.flush();
        } catch (IOException e) {
            control.checkAlive();
            throw new ReplayFailure("CONNECTION_LOST", Map.of("target", host + ":" + port, "reason", Sockets.reason(e)),
                    "Write to " + host + ":" + port + " failed: " + Sockets.reason(e));
        }
    }

    @Override
    public void closeConnection() {
        expectedClose.set(true);
        Sockets.closeQuietly(socket.get());
    }

    @Override
    public void reopen(Control control) {
        open(control);
    }

    @Override
    public boolean waitsForPeer() {
        return false;
    }

    @Override
    public void close() {
        closeConnection();
    }

}
