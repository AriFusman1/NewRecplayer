package com.pcapplayer.icd.loading.annotationJson;

import com.pcapplayer.icd.model.EFieldType;

import java.util.Map;

/**
 * How many bytes a Java type takes on the wire, following the legacy annotation mechanism:
 * <ul>
 *   <li>A Java primitive is read at its own size, signed: {@code int} is 4 bytes.</li>
 *   <li>{@code @Unsigned} on the field alone: Java has no unsigned types, so an unsigned value is kept in
 *       the next larger Java type, and only half of it is on the wire: {@code @Unsigned long} is a 4-byte
 *       unsigned, {@code @Unsigned int} 2 bytes, {@code @Unsigned short} 1 byte.</li>
 *   <li>A size given by an annotation parameter ({@code @Enumerable(byte.class)},
 *       {@code @FixedPoint(type = int.class)}) is read at that size; {@code @Unsigned} only makes it unsigned.</li>
 * </ul>
 */
final class JavaTypes {

    private static final Map<String, EFieldType> SIGNED = Map.of(
            "byte", EFieldType.INT8, "Byte", EFieldType.INT8,
            "short", EFieldType.INT16, "Short", EFieldType.INT16,
            "int", EFieldType.INT32, "Integer", EFieldType.INT32,
            "long", EFieldType.INT64, "Long", EFieldType.INT64);

    private static final Map<String, EFieldType> FLOATING = Map.of(
            "float", EFieldType.FLOAT32, "Float", EFieldType.FLOAT32,
            "double", EFieldType.FLOAT64, "Double", EFieldType.FLOAT64);

    private static final Map<EFieldType, EFieldType> UNSIGNED_OF_SIZE = Map.of(
            EFieldType.INT8, EFieldType.UINT8, EFieldType.INT16, EFieldType.UINT16,
            EFieldType.INT32, EFieldType.UINT32, EFieldType.INT64, EFieldType.UINT64);

    /** The type half the size of an unsigned field's Java type. */
    private static final Map<EFieldType, EFieldType> UNSIGNED_IN_LARGER = Map.of(
            EFieldType.INT8, EFieldType.UINT8, EFieldType.INT16, EFieldType.UINT8,
            EFieldType.INT32, EFieldType.UINT16, EFieldType.INT64, EFieldType.UINT32);

    private JavaTypes() {
    }

    static boolean isNumber(String javaType) {
        return SIGNED.containsKey(javaType) || FLOATING.containsKey(javaType);
    }

    static boolean isBoolean(String javaType) {
        return "boolean".equals(javaType) || "Boolean".equals(javaType);
    }

    /**
     * The wire type of a field declared as {@code javaType}.
     *
     * @return null if {@code javaType} is not a Java number type
     */
    static EFieldType ofField(String javaType, boolean unsigned) {
        if (FLOATING.containsKey(javaType)) return FLOATING.get(javaType);
        EFieldType signed = SIGNED.get(javaType);
        if (signed == null) return null;
        return unsigned ? UNSIGNED_IN_LARGER.get(signed) : signed;
    }

    /**
     * The wire type of an integer whose size an annotation parameter gives ({@code byte}, {@code short},
     * {@code int} or {@code long}, with or without {@code .class}).
     *
     * @return null if {@code sizeClass} is not an integer type
     */
    static EFieldType ofSizeClass(String sizeClass, boolean unsigned) {
        EFieldType signed = SIGNED.get(sizeClass.replace(".class", ""));
        if (signed == null) return null;
        return unsigned ? UNSIGNED_OF_SIZE.get(signed) : signed;
    }

    /** An integer of {@code bits} bits (8, 16, 24 or 32), or null for another size. */
    static EFieldType ofBits(int bits, boolean unsigned) {
        return switch (bits) {
            case 8 -> unsigned ? EFieldType.UINT8 : EFieldType.INT8;
            case 16 -> unsigned ? EFieldType.UINT16 : EFieldType.INT16;
            case 24 -> unsigned ? EFieldType.UINT24 : null;
            case 32 -> unsigned ? EFieldType.UINT32 : EFieldType.INT32;
            default -> null;
        };
    }
}
