package com.pcapplayer.capture;

/**
 * One captured frame as stored in the capture file.
 *
 * @param tsNanos  capture timestamp, epoch nanoseconds
 * @param linkType link-layer header type (LINKTYPE_* value)
 * @param data     captured bytes (length = captured length)
 * @param origLen  original length on the wire; {@code > data.length} means snaplen truncation
 */
public record RawFrame(long tsNanos, int linkType, byte[] data, int origLen) {

    public boolean truncated() {
        return origLen > data.length;
    }
}
