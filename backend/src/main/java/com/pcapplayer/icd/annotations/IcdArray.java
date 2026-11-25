package com.pcapplayer.icd.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Makes a field repeat. Give exactly one of {@code length} (fixed count) or {@code lengthField} (path of
 * an earlier integer field holding the count: a plain name in the same class, {@code header.x}, or a
 * dotted path from the message root). The Java field is an array; its component type describes one element.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface IcdArray {

    int length() default 0;

    String lengthField() default "";
}
