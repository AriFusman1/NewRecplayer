package com.pcapplayer.icd.model;

/** One field of a bitfield: {@code bits} bits wide, read as an unsigned number. */
public record BitDef(String name, int bits) {
}
