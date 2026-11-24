package com.pcapplayer.icd.timefix;

/** Gives the writable bytes of a script step (playback hands out copies, never the cached originals). */
@FunctionalInterface
public interface StepBytes {

    byte[] bytes(int step);
}
