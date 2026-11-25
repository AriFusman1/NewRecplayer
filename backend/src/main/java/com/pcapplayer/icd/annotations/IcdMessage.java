package com.pcapplayer.icd.annotations;

import com.pcapplayer.icd.model.EByteOrder;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a message class. Its fields (after the header) use {@link IcdField}. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface IcdMessage {

    long opcode();

    String name();

    /** Optional override for the whole message: give one value, or leave empty to use the ICD's. */
    EByteOrder[] byteOrder() default {};
}
