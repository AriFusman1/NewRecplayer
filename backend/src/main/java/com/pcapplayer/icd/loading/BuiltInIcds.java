package com.pcapplayer.icd.loading;

import java.util.List;

/**
 * The ICD versions written as annotated Java classes, listed explicitly (no classpath scanning).
 * <p>
 * To add one: create the package {@code com.pcapplayer.icd.versions.<version>} with a holder class
 * annotated {@code @Icd}, its header and message classes, then add the holder class here.
 */
public final class BuiltInIcds {

    /** Holder classes annotated with {@code @Icd}. */
    public static final List<Class<?>> HOLDERS = List.of();

    private BuiltInIcds() {
    }
}
