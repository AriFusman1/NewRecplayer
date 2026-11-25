package com.pcapplayer.icd.annotations;

import com.pcapplayer.icd.model.EByteOrder;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the holder class of one ICD version. Put it in the version's own package
 * ({@code com.pcapplayer.icd.versions.<version>}) and list it in {@code BuiltInIcds}.
 * <pre>
 * &#64;Icd(id = "radar-v2", name = "Radar ICD v2", byteOrder = EByteOrder.BIG_ENDIAN,
 *      header = RadarHeader.class, messages = {Status.class, Track.class})
 * public final class RadarIcd {}
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Icd {

    /** Stable identifier (letters, digits, '.', '_', '-'); categories and profiles refer to it. */
    String id();

    /** Display name. */
    String name();

    /** Default byte order for every field. */
    EByteOrder byteOrder() default EByteOrder.BIG_ENDIAN;

    /** The class annotated with {@link IcdHeader}. */
    Class<?> header();

    /** The classes annotated with {@link IcdMessage}. */
    Class<?>[] messages();
}
