package com.pcapplayer.autotag.rules;

/**
 * Comparison operators of a field predicate. Which ones a field accepts depends on its type
 * ({@link EValueType#allows}).
 */
public enum EOperator {
    EQ(1),
    NE(1),
    GT(1),
    GE(1),
    LT(1),
    LE(1),

    /** {@code low ≤ value ≤ high}: both ends inclusive. */
    BETWEEN(2),

    /** The value equals one of the listed values. */
    IN(-1),

    /** The value equals none of the listed values. */
    NOT_IN(-1),

    /** Strings only, case-sensitive. */
    CONTAINS(1),

    /** Strings only, case-sensitive. */
    STARTS_WITH(1);

    private final int valueCount;

    EOperator(int valueCount) {
        this.valueCount = valueCount;
    }

    /** How many values the operator takes; -1 means "one or more" (a list). */
    public int valueCount() {
        return valueCount;
    }
}
