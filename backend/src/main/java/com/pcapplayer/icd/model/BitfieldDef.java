package com.pcapplayer.icd.model;

import java.util.List;

/**
 * Bit fields packed in one integer.
 *
 * @param type     the containing integer type
 * @param bitOrder where the first bit field sits in the container
 * @param bits     the bit fields in order; together at most the container's size. Unused bits are ignored.
 * @param flat     true: each bit field is addressed by its own name, like a field of the enclosing struct
 *                 (legacy {@code @Bits}); false: as {@code <bitfield>.<bit>}
 */
public record BitfieldDef(EFieldType type, EBitOrder bitOrder, List<BitDef> bits, boolean flat) {

    public BitfieldDef(EFieldType type, EBitOrder bitOrder, List<BitDef> bits) {
        this(type, bitOrder, bits, false);
    }

    /** The path of one bit field, given the path of the bitfield and the prefix of the enclosing struct. */
    public String bitPath(String bitfieldPath, String structPrefix, BitDef bit) {
        return flat ? structPrefix + bit.name() : bitfieldPath + "." + bit.name();
    }
}
