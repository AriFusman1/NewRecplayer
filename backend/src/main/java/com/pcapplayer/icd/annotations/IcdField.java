package com.pcapplayer.icd.annotations;

import com.pcapplayer.icd.model.EByteOrder;
import com.pcapplayer.icd.model.EFieldType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field of a header, message, struct or bitfield class. Only {@code order} decides the field's
 * position; the order of declaration in Java is ignored.
 * <p>
 * {@code type} is needed for numbers whose Java type does not say it (unsigned types, or a Java type
 * that differs from the wire type). It is not used for structs, enums, strings, bitfields or padding,
 * which take their layout from their own annotation or Java type.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface IcdField {

    int order();

    /** The wire type, one value. Empty: inferred from a Java primitive (byte→INT8 … double→FLOAT64). */
    EFieldType[] type() default {};

    /** Optional byte-order override, one value. */
    EByteOrder[] byteOrder() default {};
}
