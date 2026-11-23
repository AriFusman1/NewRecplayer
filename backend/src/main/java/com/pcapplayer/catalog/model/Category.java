package com.pcapplayer.catalog.model;

import com.pcapplayer.pcap.script.EConnectionMode;

/** A record category; its connection mode is fixed at creation. */
public record Category(String id, String name, EConnectionMode mode, long createdAtMs) {
}
