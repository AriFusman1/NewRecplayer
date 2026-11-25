package com.pcapplayer.icd.annotations;

import com.pcapplayer.icd.model.ELengthUnit;
import com.pcapplayer.icd.model.HeaderDef;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the class describing the header every message starts with. Its fields use {@link IcdField};
 * one is marked {@link Opcode} and one {@link Length}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface IcdHeader {

    /** True if the length field counts the header too, false if only the message body. */
    boolean lengthIncludesHeader() default true;

    ELengthUnit lengthUnit() default ELengthUnit.BYTES;

    /** A larger length is treated as corrupt data. */
    int maxMessageBytes() default HeaderDef.DEFAULT_MAX_MESSAGE_BYTES;
}
