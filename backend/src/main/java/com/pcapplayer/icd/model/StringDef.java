package com.pcapplayer.icd.model;

/**
 * A fixed-length byte string, padded with zero bytes.
 *
 * @param length   size in bytes
 * @param encoding Java charset name, US-ASCII by default
 */
public record StringDef(int length, String encoding) {

    public static final String DEFAULT_ENCODING = "US-ASCII";
}
