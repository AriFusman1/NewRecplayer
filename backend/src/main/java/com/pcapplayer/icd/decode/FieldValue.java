package com.pcapplayer.icd.decode;

import com.pcapplayer.icd.model.EFieldKind;
import com.pcapplayer.icd.model.EFieldType;

import java.math.BigInteger;

/**
 * One decoded value.
 *
 * @param kind     PRIMITIVE, ENUM, STRING or BITFIELD (a bit field's value)
 * @param type     the stored type (for a bit field: its container); null for a string
 * @param value    {@link Long} for integers (a UINT64 above Long.MAX_VALUE is a {@link BigInteger}),
 *                 {@link Double} for floats, {@link Boolean}, or {@link String}
 * @param enumName for an enum, the value's name; null when the value has no name
 */
public record FieldValue(EFieldKind kind, EFieldType type, Object value, String enumName) {

    /** True for integer and float values, including enums and bit fields (not BOOL or strings). */
    public boolean numeric() {
        return value instanceof Number;
    }

    /** The value as a long. A UINT64 above Long.MAX_VALUE gives its raw two's-complement bits. */
    public long asLong() {
        return ((Number) value).longValue();
    }

    public double asDouble() {
        return ((Number) value).doubleValue();
    }
}
