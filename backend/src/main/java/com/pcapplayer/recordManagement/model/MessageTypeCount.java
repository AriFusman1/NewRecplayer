package com.pcapplayer.recordManagement.model;

/** How many messages of one type a file contains. */
public record MessageTypeCount(long opcode, String name, long count) {
}
