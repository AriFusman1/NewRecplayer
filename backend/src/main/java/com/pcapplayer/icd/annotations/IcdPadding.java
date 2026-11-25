package com.pcapplayer.icd.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Reserved bytes that are skipped. The Java type does not matter ({@code byte[]} reads well). */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface IcdPadding {

    int bytes();
}
