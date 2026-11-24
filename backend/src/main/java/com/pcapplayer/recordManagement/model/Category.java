package com.pcapplayer.recordManagement.model;

import com.pcapplayer.pcapProcessing.script.EConnectionMode;

/** A record category; its connection mode is fixed at creation. */
public record Category(String id, String name, EConnectionMode mode, long createdAtMs) {
}
