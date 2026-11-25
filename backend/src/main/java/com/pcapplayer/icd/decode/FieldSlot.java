package com.pcapplayer.icd.decode;

import com.pcapplayer.icd.model.EByteOrder;
import com.pcapplayer.icd.model.EFieldType;

/**
 * Where a decoded value sits inside its message, and how it is stored: enough to write a new value
 * back in place.
 *
 * @param offset    offset of the field's first byte from the start of the message (header included)
 * @param size      bytes taken; for a bit field, the size of its containing integer
 * @param type      the stored type; for a bit field, the container's type
 * @param bitShift  for a bit field: how far its lowest bit is from the container's lowest bit; else 0
 * @param bitWidth  for a bit field: its width in bits; 0 for an ordinary field
 */
public record FieldSlot(int offset, int size, EFieldType type, EByteOrder byteOrder, int bitShift, int bitWidth) {

    public static FieldSlot whole(int offset, EFieldType type, EByteOrder byteOrder) {
        return new FieldSlot(offset, type.size(), type, byteOrder, 0, 0);
    }

    public boolean bitField() {
        return bitWidth > 0;
    }
}
