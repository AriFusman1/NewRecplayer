package com.pcapplayer.icd.model;

/** Whether a decoded value passed its {@link ValueRules}. */
public enum EValidity {

    /** No rule says otherwise. */
    VALID,

    /** The stored value is the field's invalidity value. */
    INVALIDITY_VALUE,

    /** The real value is outside the valid range. */
    OUT_OF_RANGE
}
