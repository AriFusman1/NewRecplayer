package com.pcapplayer.playback.transport;

import com.pcapplayer.playback.Control;
import com.pcapplayer.playback.ReplayFailure;

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
import java.util.concurrent.atomic.AtomicReference;

/**
 * TCP-client mode: the replayer is the TCP server. It listens on a port and waits for the service
 * to connect. While a client is connected (or one is queued) further connections are refused
 * (accepted and closed at once).
 */
public class TcpListenTransport implements Transport {

    private final int port;
    private ServerSocket server;
    private final BlockingQueue<Socket> incoming = new LinkedBlockingQueue<>();
    private final AtomicBoolean slotTaken = new AtomicBoolean();
    private final AtomicReference<Socket> client = new AtomicReference<>();
    private OutputStream out; // replay thread only
    private final AtomicBoolean expectedClose = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public TcpListenTransport(int port) {
        this.port = port;
    }

    /** Binds the port now so "port in use" is reported before the replay is accepted. */
    public void bind() {
        try {
            ServerSocket bound = new ServerSocket();
            bound.setReuseAddress(true);
            bound.bind(new InetSocketAddress(port));
            server = bound;
        } catch (IOException e) {
            throw new ReplayFailure("LISTEN_FAILED", Map.of("port", port, "reason", Sockets.reason(e)),
                    "Cannot listen on port " + port + ": " + Sockets.reason(e));
        }
        Thread acceptor = new Thread(this::acceptLoop, "accept-" + port);
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private void acceptLoop() {
        while (!closed.get()) {
            try {
                Socket accepted = server.accept();

                if (slotTaken.compareAndSet(false, true)) {
                    incoming.add(accepted);
                } else {
                    accepted.setSoLinger(true, 0); // refuse: reset immediately
                    accepted.close();
                }
            } catch (IOException e) {
                if (closed.get()) return;
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
        Socket connected;

        try {
            do {
                control.checkAlive();
                connected = incoming.poll(50, TimeUnit.MILLISECONDS);
            } while (connected == null);
        } catch (InterruptedException e) {
            control.checkAlive();
            throw new IllegalStateException(e);
        }

        try {
            connected.setTcpNoDelay(true);
            out = connected.getOutputStream();
        } catch (IOException e) {
            throw new ReplayFailure("CLIENT_DISCONNECTED", Map.of("port", port, "reason", Sockets.reason(e)),
                    "Client disconnected: " + Sockets.reason(e));
        }
        expectedClose.set(false);
        client.set(connected);
        final Socket accepted = connected;

        Drainer.start(connected, "drain-listen-" + port, reason -> {
            if (!expectedClose.get() && client.get() == accepted) {
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
            throw new ReplayFailure("CLIENT_DISCONNECTED", Map.of("port", port, "reason", Sockets.reason(e)),
                    "Client disconnected from port " + port + ": " + Sockets.reason(e));
        }
    }

    @Override
    public void closeConnection() {
        expectedClose.set(true);
        Sockets.closeQuietly(client.getAndSet(null));
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
        closed.set(true);
        closeConnection();
        Sockets.closeQuietly(server);
        Socket leftover;
        while ((leftover = incoming.poll()) != null) Sockets.closeQuietly(leftover);
    }
}
