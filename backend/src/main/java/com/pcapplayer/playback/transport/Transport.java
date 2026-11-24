package com.pcapplayer.playback.transport;

import com.pcapplayer.playback.Control;

/** Delivers a script's bytes for one connection mode. Never reads replies (they are drained and discarded). */
public interface Transport extends AutoCloseable {

    /** Opens the first connection; TCP_CLIENT blocks until the service connects. */
    void open(Control control);

    /**
     * One UDP datagram / one TCP write.
     *
     * @return false if it was not delivered because there is no connection right now (only a
     *         transport that keeps trying does that; the others throw a ReplayFailure instead)
     */
    boolean send(byte[] data, Control control);

    /** False while a transport that keeps trying has no connection. */
    default boolean isConnected() {
        return true;
    }

    /** The recording shows the connection closing here. */
    void closeConnection();

    /** The recording shows a new connection opening here. */
    void reopen(Control control);

    /** True if (re)opening waits for the service to connect to us, so the schedule shifts by the wait. */
    boolean waitsForPeer();

    @Override
    void close();
}
