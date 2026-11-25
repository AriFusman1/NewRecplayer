package com.pcapplayer.icd.model;

/** What a field holds. An array is not a kind: any field can be repeated with {@link ArrayDef}. */
public enum EFieldKind {

    /** A number or BOOL; see {@link EFieldType}. */
    PRIMITIVE,

    /** An integer with value → name mapping. */
    ENUM,

    /** A nested group of fields. */
    STRUCT,

    /** A fixed-length, null-padded byte string. */
    STRING,

    /** Several small fields packed inside one integer. */
    BITFIELD,

    /** Reserved bytes, skipped. */
    PADDING
}
