package com.pcapplayer.replay;

/** Thrown inside a replay thread when someone pressed Stop. */
public class StoppedException extends RuntimeException {

    public StoppedException() {
        super("stopped", null, false, false);
    }
}
