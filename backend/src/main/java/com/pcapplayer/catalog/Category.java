package com.pcapplayer.catalog;

import com.pcapplayer.extract.ConnectionMode;

/** A record category; its connection mode is fixed at creation. */
public record Category(String id, String name, ConnectionMode mode, long createdAtMs) {
}
