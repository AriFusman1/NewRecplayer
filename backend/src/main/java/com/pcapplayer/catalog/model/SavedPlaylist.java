package com.pcapplayer.catalog.model;

import java.util.List;

/** A named playlist kept on the server so everyone can load, edit and play it. */
public record SavedPlaylist(String id, String name, List<Item> items, String createdBy, long createdAtMs,
                            String updatedBy, long updatedAtMs) {

    /** @param host null for TCP_CLIENT (listen) records */
    public record Item(String recordId, String host, int port, long sleepAfterMs) {
    }
}
