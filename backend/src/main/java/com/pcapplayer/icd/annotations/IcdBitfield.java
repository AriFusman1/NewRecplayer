package com.pcapplayer.icd.annotations;

import com.pcapplayer.icd.model.EBitOrder;
import com.pcapplayer.icd.model.EFieldType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class whose fields are bit fields packed in one integer of {@code type}. Each of its fields
 * has {@link IcdField} (for the order) and {@link IcdBits}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface IcdBitfield {

    EFieldType type();

    EBitOrder bitOrder() default EBitOrder.MSB_FIRST;
}
