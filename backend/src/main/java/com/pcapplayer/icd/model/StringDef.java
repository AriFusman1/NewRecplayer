package com.pcapplayer.icd.model;

/**
 * A fixed-length byte string, padded with zero bytes.
 *
 * @param length   size in bytes
 * @param encoding Java charset name, US-ASCII by default
 * @param reversed the characters are stored last to first, as legacy systems store Hebrew
 *                 ({@code @Charset(isReversed = true)})
 */
public record StringDef(int length, String encoding, boolean reversed) {

    public static final String DEFAULT_ENCODING = "US-ASCII";

    public StringDef(int length, String encoding) {
        this(length, encoding, false);
    }
}
