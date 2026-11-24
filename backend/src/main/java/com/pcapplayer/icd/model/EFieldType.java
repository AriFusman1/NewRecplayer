package com.pcapplayer.icd.model;

/** Primitive data types. The type fixes the size. */
public enum EFieldType {
    INT8(1, true, false),
    UINT8(1, false, false),
    INT16(2, true, false),
    UINT16(2, false, false),
    INT32(4, true, false),
    UINT32(4, false, false),
    INT64(8, true, false),
    UINT64(8, false, false),
    FLOAT32(4, true, true),
    FLOAT64(8, true, true),
    BOOL(1, false, false);

    private final int size;
    private final boolean signed;
    private final boolean floating;

    EFieldType(int size, boolean signed, boolean floating) {
        this.size = size;
        this.signed = signed;
        this.floating = floating;
    }

    /** Size in bytes. */
    public int size() {
        return size;
    }

    public boolean signed() {
        return signed;
    }

    public boolean floating() {
        return floating;
    }

    /** True for the integer types (not float, not BOOL). */
    public boolean integer() {
        return !floating && this != BOOL;
    }

    /** True for the types a number can be stored in: integers and floats. */
    public boolean numeric() {
        return this != BOOL;
    }

    /** Smallest value an integer type can hold (for UINT64 the raw long 0). */
    public long minValue() {
        if (!signed) return 0;
        return size == 8 ? Long.MIN_VALUE : -(1L << (size * 8 - 1));
    }

    /** Largest value an integer type can hold; UINT64 is limited to {@link Long#MAX_VALUE} here. */
    public long maxValue() {
        if (size == 8) return Long.MAX_VALUE;
        return signed ? (1L << (size * 8 - 1)) - 1 : (1L << (size * 8)) - 1;
    }
}
