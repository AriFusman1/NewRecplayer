package com.pcapplayer.replay;

/** A record to play, its target and the pause after it (playlists only). */
public record ItemRequest(String recordId, Target target, long sleepAfterMs) {
}
