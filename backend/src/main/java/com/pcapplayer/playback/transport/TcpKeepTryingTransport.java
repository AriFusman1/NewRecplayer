package com.pcapplayer.playback.transport;

import com.pcapplayer.playback.Control;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * TCP-server mode that never fails for want of a connection: the replayer is the TCP client, but
 * if the service is not listening (or the connection drops) playback keeps its schedule and a
 * background thread retries the connection every {@code retryMs}. A message whose time comes while
 * there is no connection is not delivered ({@link #send} returns false). Once connected, the
 * service gets the messages from that point on.
 * <p>
 * The socket is swapped under {@code this}: the replay thread sends, the retry thread connects.
 */
public class TcpKeepTryingTransport implements Transport {

    /** What the session log shows; called from the replay thread or the retry thread. */
    public interface Events {
        void connected();

        /** @param reason why there is no connection now (refused, timed out, dropped) */
        void notConnected(String reason, boolean wasConnected);
    }

    private final String host;
    private final int port;
    private final int connectTimeoutMs;
    private final long retryMs;
    private final Events events;

    private Socket socket;      // guarded by this; null = not connected
    private OutputStream out;   // guarded by this
    private Thread retrier;     // guarded by this; null = not retrying
    /** A connection is wanted: false between a recorded close and its reopen, and after close(). */
    private boolean wanted;     // guarded by this
    private boolean closed;     // guarded by this

    public TcpKeepTryingTransport(String host, int port, int connectTimeoutMs, long retryMs, Events events) {
        this.host = host;
        this.port = port;
        this.connectTimeoutMs = connectTimeoutMs;
        this.retryMs = retryMs;
        this.events = events;
    }

    /** Tries once now; if that fails, playback starts anyway and the retry thread takes over. */
    @Override
    public void open(Control control) {
        synchronized (this) {
            if (closed) return;
            wanted = true;
        }
        String failure = tryConnect();

        if (failure != null) {
            events.notConnected(failure, false);
            startRetrying();
        }
    }

    /** @return false if there is no connection, or the write failed (the connection is then retried) */
    @Override
    public boolean send(byte[] data, Control control) {
        Socket current;

        synchronized (this) {
            if (out == null) return false;
            current = socket;

            try {
                out.write(data);
                out.flush();
                return true;
            } catch (IOException e) {
                // fall through: handled outside the lock
            }
        }
        lost(current, "write failed");
        return false;
    }

    @Override
    public synchronized boolean isConnected() {
        return socket != null;
    }

    /** The recording closes the connection here: close it and stop retrying until the reopen. */
    @Override
    public void closeConnection() {
        Socket old;

        synchronized (this) {
            wanted = false;
            old = socket;
            socket = null;
            out = null;
            stopRetrying();
        }
        Sockets.closeQuietly(old);
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
        synchronized (this) {
            closed = true;
        }
        closeConnection();
    }

    // ------------------------------------------------------------------ connecting

    /** One connection attempt. @return null when connected, otherwise why not */
    private String tryConnect() {
        Socket connecting = new Socket();

        try {
            connecting.setTcpNoDelay(true);
            connecting.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        } catch (IOException e) {
            Sockets.closeQuietly(connecting);
            return Sockets.reason(e);
        }

        synchronized (this) {
            if (!wanted || closed) {
                Sockets.closeQuietly(connecting);
                return null; // nobody wants it any more; not a failure
            }

            try {
                out = connecting.getOutputStream();
            } catch (IOException e) {
                Sockets.closeQuietly(connecting);
                return Sockets.reason(e);
            }
            socket = connecting;
        }
        // replies are read and discarded; the read ending means the service closed the connection
        Drainer.start(connecting, "drain-" + host + ":" + port, reason -> lost(connecting, reason));
        events.connected();
        return null;
    }

    /** The connection {@code which} dropped: forget it and retry (unless it was already replaced or closed on purpose). */
    private void lost(Socket which, String reason) {
        synchronized (this) {
            if (socket != which || which == null) return;
            socket = null;
            out = null;
        }
        Sockets.closeQuietly(which);
        events.notConnected(reason, true);
        startRetrying();
    }

    private synchronized void startRetrying() {
        if (retrier != null || !wanted || closed) return;
        retrier = new Thread(this::retryLoop, "tcp-retry-" + host + ":" + port);
        retrier.setDaemon(true);
        retrier.start();
    }

    private synchronized void stopRetrying() {
        if (retrier != null) retrier.interrupt();
        retrier = null;
    }

    private void retryLoop() {
        Thread me = Thread.currentThread();

        while (!me.isInterrupted()) {
            try {
                Thread.sleep(retryMs);
            } catch (InterruptedException e) {
                return;
            }

            synchronized (this) {
                if (retrier != me || !wanted || closed) return;
            }

            if (tryConnect() == null) {
                synchronized (this) {
                    if (retrier == me) retrier = null;
                }
                return;
            }
        }
    }
}
