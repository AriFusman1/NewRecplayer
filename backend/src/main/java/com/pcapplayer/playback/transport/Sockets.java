package com.pcapplayer.playback.transport;

/** Socket helpers shared by the transports. */
public final class Sockets {

    private Sockets() {
    }

    /** A short human-readable cause for a socket error. */
    static String reason(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /** Closes, ignoring errors (used when tearing down or unblocking). */
    static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) return;

        try {
            closeable.close();
        } catch (Exception ignored) {
            // best effort
        }
    }
}
