package com.pcapplayer.playback;

/** What a session is doing right now (shown in Now Playing). */
public enum EItemState {
    PREPARING,
    /** Played together with other records: ready, waiting until all of them are ready. */
    WAITING_FOR_GROUP,
    CONNECTING, WAITING_FOR_CONNECTION, PLAYING, SLEEPING
}
