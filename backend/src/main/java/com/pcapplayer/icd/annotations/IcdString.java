package com.pcapplayer.icd.annotations;

import com.pcapplayer.icd.model.StringDef;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A fixed-length string of {@code length} bytes, padded with zero bytes. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface IcdString {

    int length();

    /** A Java charset name. */
    String encoding() default StringDef.DEFAULT_ENCODING;
}
