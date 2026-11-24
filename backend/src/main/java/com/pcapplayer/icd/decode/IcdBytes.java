package com.pcapplayer.icd.decode;

import com.pcapplayer.icd.model.EByteOrder;
import com.pcapplayer.icd.model.EFieldType;

import java.math.BigInteger;

/** Reads and writes fixed-size numbers in a byte array, in either byte order. */
public final class IcdBytes {

    private IcdBytes() {
    }

    /** The {@code size} bytes at {@code offset} as an unsigned number (all 64 bits for size 8). */
    public static long readUnsigned(byte[] bytes, int offset, int size, EByteOrder byteOrder) {
        long raw = 0;

        for (int i = 0; i < size; i++) {
            int index = byteOrder == EByteOrder.BIG_ENDIAN ? offset + i : offset + size - 1 - i;
            raw = (raw << 8) | (bytes[index] & 0xFF);
        }
        return raw;
    }

    /** Writes the low {@code size} bytes of {@code raw} at {@code offset}. */
    public static void writeUnsigned(byte[] bytes, int offset, int size, EByteOrder byteOrder, long raw) {
        for (int i = 0; i < size; i++) {
            int index = byteOrder == EByteOrder.BIG_ENDIAN ? offset + size - 1 - i : offset + i;
            bytes[index] = (byte) (raw >>> (8 * i));
        }
    }

    /** Sign-extends the low {@code size} bytes of {@code raw}. */
    public static long signExtend(long raw, int size) {
        int unused = 64 - size * 8;
        return (raw << unused) >> unused;
    }

    /** The Java value of a stored number: Long (BigInteger for large UINT64), Double or Boolean. */
    public static Object toValue(long raw, EFieldType type) {
        return switch (type) {
            case FLOAT32 -> (double) Float.intBitsToFloat((int) raw);
            case FLOAT64 -> Double.longBitsToDouble(raw);
            case BOOL -> raw != 0;
            case UINT64 -> raw >= 0 ? (Object) raw : new BigInteger(Long.toUnsignedString(raw));
            default -> type.signed() ? signExtend(raw, type.size()) : raw;
        };
    }
}
