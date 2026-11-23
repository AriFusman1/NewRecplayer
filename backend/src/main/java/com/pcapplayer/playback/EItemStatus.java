package com.pcapplayer.playback;

/** Where one session item is in its life: waiting its turn, playing, or how it ended. */
public enum EItemStatus {
    PENDING, PLAYING, COMPLETED, STOPPED, FAILED
}
