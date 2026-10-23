package com.pcapplayer.replay.transport;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;

/**
 * Reads and discards everything the service sends so its replies can never fill the socket
 * buffers and block it. Reports when the peer goes away.
 */
final class Drainer {

    interface OnClosed {
        void closed(String reason);
    }

    private Drainer() {
    }

    static void start(Socket socket, String name, OnClosed onClosed) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[64 * 1024];
            String reason = "closed by peer";
            try (InputStream in = socket.getInputStream()) {
                while (in.read(buf) >= 0) {
                    // discard
                }
            } catch (IOException e) {
                reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            onClosed.closed(reason);
        }, name);
        t.setDaemon(true);
        t.start();
    }
}
