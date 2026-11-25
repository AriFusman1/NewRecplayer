package com.pcapplayer.icd.model;

/** Unit of the header's length field. */
public enum ELengthUnit {
    BYTES(1),
    WORDS16(2),
    WORDS32(4);

    private final int bytes;

    ELengthUnit(int bytes) {
        this.bytes = bytes;
    }

    /** Bytes per unit. */
    public int bytes() {
        return bytes;
    }
}
