package com.pcapplayer.replay.transport;

import com.pcapplayer.replay.Control;
import com.pcapplayer.replay.ReplayFailure;
import com.pcapplayer.replay.StoppedException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;

/** TCP-server mode: the replayer is the TCP client and connects to the service. */
public final class TcpConnectTransport implements Transport {

    private final String host;
    private final int port;
    private final int connectTimeoutMs;
    private volatile Socket socket;
    private OutputStream out;
    private volatile boolean expectedClose;

    public TcpConnectTransport(String host, int port, int connectTimeoutMs) {
        this.host = host;
        this.port = port;
        this.connectTimeoutMs = connectTimeoutMs;
    }

    @Override
    public void open(Control control) {
        Socket s = new Socket();
        control.closeOnAbort(s);
        try {
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            out = s.getOutputStream();
        } catch (IOException e) {
            closeQuietly(s);
            control.checkAlive();
            throw new ReplayFailure("CONNECT_FAILED", Map.of("target", host + ":" + port, "reason", reason(e)),
                    "Connect to " + host + ":" + port + " failed: " + reason(e));
        }
        expectedClose = false;
        socket = s;
        Drainer.start(s, "drain-" + host + ":" + port, reason -> {
            if (!expectedClose && socket == s) {
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
            throw new ReplayFailure("CONNECTION_LOST", Map.of("target", host + ":" + port, "reason", reason(e)),
                    "Write to " + host + ":" + port + " failed: " + reason(e));
        }
    }

    @Override
    public void closeConnection() {
        expectedClose = true;
        closeQuietly(socket);
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

    static String reason(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    static void closeQuietly(AutoCloseable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) {
            // best effort
        }
    }
}
