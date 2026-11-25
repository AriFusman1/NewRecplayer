package com.pcapplayer.icd.annotations;

import com.pcapplayer.icd.model.EFieldType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a Java enum used as an ICD enum, stored as {@code type}. Every constant has {@link IcdEnumValue}.
 * A field whose Java type is this enum becomes an enum field.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface IcdEnum {

    EFieldType type();

    /** The enum's name in the ICD; empty to use the Java name without the project's {@code E} prefix. */
    String name() default "";
}
