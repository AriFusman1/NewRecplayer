package com.pcapplayer.autotag.rules;

import com.pcapplayer.icd.decode.DecodedMessage;
import com.pcapplayer.icd.decode.FieldValue;
import com.pcapplayer.icd.model.EFieldKind;
import com.pcapplayer.icd.model.EFieldType;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Decoded values and messages built by hand, for rule tests. */
final class Values {

    private Values() {
    }

    static FieldValue integer(EFieldType type, long value) {
        return new FieldValue(EFieldKind.PRIMITIVE, type, value, null);
    }

    static FieldValue bigUnsigned(BigInteger value) {
        return new FieldValue(EFieldKind.PRIMITIVE, EFieldType.UINT64, value, null);
    }

    static FieldValue floating(double value) {
        return new FieldValue(EFieldKind.PRIMITIVE, EFieldType.FLOAT64, value, null);
    }

    static FieldValue enumValue(long value, String name) {
        return new FieldValue(EFieldKind.ENUM, EFieldType.INT8, value, name);
    }

    static FieldValue bool(boolean value) {
        return new FieldValue(EFieldKind.PRIMITIVE, EFieldType.BOOL, value, null);
    }

    static FieldValue text(String value) {
        return new FieldValue(EFieldKind.STRING, null, value, null);
    }

    /** A message with the given path → value pairs, captured at {@code tsNanos}. */
    static DecodedMessage message(long opcode, long tsNanos, Object... pathsAndValues) {
        Map<String, FieldValue> fields = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pathsAndValues.length; i += 2) fields.put((String) pathsAndValues[i], (FieldValue) pathsAndValues[i + 1]);
        return new DecodedMessage(opcode, "M" + opcode, 0, null, tsNanos, 0, 0, 0, 0, fields, Map.of(), List.of());
    }
}
