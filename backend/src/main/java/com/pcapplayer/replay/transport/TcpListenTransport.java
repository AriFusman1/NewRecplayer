package com.pcapplayer.replay.transport;

import com.pcapplayer.replay.Control;
import com.pcapplayer.replay.ReplayFailure;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TCP-client mode: the replayer is the TCP server. It listens on a port and waits for the service
 * to connect. While a client is connected (or one is queued) further connections are refused
 * (accepted and closed at once).
 */
public final class TcpListenTransport implements Transport {

    private final int port;
    private ServerSocket server;
    private final BlockingQueue<Socket> incoming = new LinkedBlockingQueue<>();
    private final AtomicBoolean slotTaken = new AtomicBoolean();
    private volatile Socket client;
    private OutputStream out;
    private volatile boolean expectedClose;
    private volatile boolean closed;

    public TcpListenTransport(int port) {
        this.port = port;
    }

    /** Binds the port now so "port in use" is reported before the replay is accepted. */
    public void bind() {
        try {
            ServerSocket s = new ServerSocket();
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(port));
            server = s;
        } catch (IOException e) {
            throw new ReplayFailure("LISTEN_FAILED", Map.of("port", port, "reason", TcpConnectTransport.reason(e)),
                    "Cannot listen on port " + port + ": " + TcpConnectTransport.reason(e));
        }
        Thread acceptor = new Thread(this::acceptLoop, "accept-" + port);
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket s = server.accept();
                if (slotTaken.compareAndSet(false, true)) {
                    incoming.add(s);
                } else {
                    s.setSoLinger(true, 0); // refuse: reset immediately
                    s.close();
                }
            } catch (IOException e) {
                if (closed) return;
            }
        }
    }

    @Override
    public void open(Control control) {
        if (server == null) bind();
        control.closeOnAbort(this);
        awaitClient(control);
    }

    private void awaitClient(Control control) {
        Socket s;
        try {
            do {
                control.checkAlive();
                s = incoming.poll(50, TimeUnit.MILLISECONDS);
            } while (s == null);
        } catch (InterruptedException e) {
            control.checkAlive();
            throw new IllegalStateException(e);
        }
        try {
            s.setTcpNoDelay(true);
            out = s.getOutputStream();
        } catch (IOException e) {
            throw new ReplayFailure("CLIENT_DISCONNECTED", Map.of("port", port, "reason", TcpConnectTransport.reason(e)),
                    "Client disconnected: " + TcpConnectTransport.reason(e));
        }
        expectedClose = false;
        client = s;
        final Socket accepted = s;
        Drainer.start(s, "drain-listen-" + port, reason -> {
            if (!expectedClose && client == accepted) {
                control.fail(new ReplayFailure("CLIENT_DISCONNECTED", Map.of("port", port, "reason", reason),
                        "Client disconnected from port " + port + ": " + reason));
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
            throw new ReplayFailure("CLIENT_DISCONNECTED", Map.of("port", port, "reason", TcpConnectTransport.reason(e)),
                    "Client disconnected from port " + port + ": " + TcpConnectTransport.reason(e));
        }
    }

    @Override
    public void closeConnection() {
        expectedClose = true;
        TcpConnectTransport.closeQuietly(client);
        client = null;
        slotTaken.set(false);
    }

    @Override
    public void reopen(Control control) {
        awaitClient(control);
    }

    @Override
    public boolean waitsForPeer() {
        return true;
    }

    @Override
    public void close() {
        closed = true;
        closeConnection();
        TcpConnectTransport.closeQuietly(server);
        Socket s;
        while ((s = incoming.poll()) != null) TcpConnectTransport.closeQuietly(s);
    }
}
