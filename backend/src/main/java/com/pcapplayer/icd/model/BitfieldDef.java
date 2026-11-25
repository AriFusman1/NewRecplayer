package com.pcapplayer.icd.model;

import java.util.List;

/**
 * Bit fields packed in one integer.
 *
 * @param type     the containing integer type
 * @param bitOrder where the first bit field sits in the container
 * @param bits     the bit fields in order; together at most the container's size. Unused bits are ignored.
 */
public record BitfieldDef(EFieldType type, EBitOrder bitOrder, List<BitDef> bits) {
}
