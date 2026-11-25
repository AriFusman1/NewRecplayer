package com.pcapplayer.icd.decode;

/**
 * A run of bytes inside one script step (one packet as replayed).
 *
 * @param step   index of the {@code Send} step in the replay script
 * @param offset offset of the first byte inside that step's data
 * @param length number of bytes
 */
public record ByteSpan(int step, int offset, int length) {
}
