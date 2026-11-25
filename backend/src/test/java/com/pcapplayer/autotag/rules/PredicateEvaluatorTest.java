package com.pcapplayer.autotag.rules;

import com.pcapplayer.icd.decode.FieldValue;
import com.pcapplayer.icd.model.EFieldType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

import static com.pcapplayer.autotag.rules.Values.bigUnsigned;
import static com.pcapplayer.autotag.rules.Values.bool;
import static com.pcapplayer.autotag.rules.Values.enumValue;
import static com.pcapplayer.autotag.rules.Values.floating;
import static com.pcapplayer.autotag.rules.Values.integer;
import static com.pcapplayer.autotag.rules.Values.text;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every operator for every field type. */
class PredicateEvaluatorTest {

    private static final double TOLERANCE = 1e-6;

    private static ValueTest test(EValueType type, EOperator op, RuleValue... values) {
        return PredicateEvaluator.compile(new FieldPredicate("x", op, List.of(values)), type, TOLERANCE);
    }

    private static ValueTest integerTest(EOperator op, long... numbers) {
        RuleValue[] values = new RuleValue[numbers.length];
        for (int i = 0; i < numbers.length; i++) values[i] = RuleValue.number(numbers[i]);
        return test(EValueType.INTEGER, op, values);
    }

    private static ValueTest floatTest(EOperator op, double... numbers) {
        RuleValue[] values = new RuleValue[numbers.length];
        for (int i = 0; i < numbers.length; i++) values[i] = RuleValue.number(numbers[i]);
        return test(EValueType.FLOAT, op, values);
    }

    private static FieldValue int32(long value) {
        return integer(EFieldType.INT32, value);
    }

    @Test
    void integerComparisons() {
        assertTrue(integerTest(EOperator.EQ, 5).test(int32(5)));
        assertFalse(integerTest(EOperator.EQ, 5).test(int32(6)));
        assertTrue(integerTest(EOperator.NE, 5).test(int32(6)));
        assertFalse(integerTest(EOperator.NE, 5).test(int32(5)));
        assertTrue(integerTest(EOperator.GT, 5).test(int32(6)));
        assertFalse(integerTest(EOperator.GT, 5).test(int32(5)));
        assertTrue(integerTest(EOperator.GE, 5).test(int32(5)));
        assertFalse(integerTest(EOperator.GE, 5).test(int32(4)));
        assertTrue(integerTest(EOperator.LT, -5).test(int32(-6)));
        assertFalse(integerTest(EOperator.LT, -5).test(int32(-5)));
        assertTrue(integerTest(EOperator.LE, -5).test(int32(-5)));
        assertFalse(integerTest(EOperator.LE, -5).test(int32(-4)));
    }

    @Test
    void betweenIncludesBothEnds() {
        ValueTest between = integerTest(EOperator.BETWEEN, 10, 20);

        assertTrue(between.test(int32(10)));
        assertTrue(between.test(int32(20)));
        assertTrue(between.test(int32(15)));
        assertFalse(between.test(int32(9)));
        assertFalse(between.test(int32(21)));

        ValueTest floatBetween = floatTest(EOperator.BETWEEN, 1.5, 2.5);
        assertTrue(floatBetween.test(floating(1.5)));
        assertTrue(floatBetween.test(floating(2.5)));
        assertFalse(floatBetween.test(floating(2.5000001)));
    }

    @Test
    void inAndNotInLists() {
        assertTrue(integerTest(EOperator.IN, 1, 3, 5).test(int32(3)));
        assertFalse(integerTest(EOperator.IN, 1, 3, 5).test(int32(4)));
        assertTrue(integerTest(EOperator.NOT_IN, 1, 3, 5).test(int32(4)));
        assertFalse(integerTest(EOperator.NOT_IN, 1, 3, 5).test(int32(5)));
    }

    @Test
    void uint64AboveLongMaxIsComparedExactly() {
        BigInteger max = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
        ValueTest equalsMax = test(EValueType.INTEGER, EOperator.EQ, new RuleValue(new BigDecimal(max), null, null, null));

        assertTrue(equalsMax.test(bigUnsigned(max)));
        assertFalse(equalsMax.test(integer(EFieldType.UINT64, Long.MAX_VALUE)));
        assertTrue(integerTest(EOperator.GT, Long.MAX_VALUE).test(bigUnsigned(max)));
        assertTrue(test(EValueType.INTEGER, EOperator.LT, new RuleValue(new BigDecimal(max), null, null, null))
                .test(integer(EFieldType.UINT64, 7)));
    }

    @Test
    void floatEqualityUsesTheTolerance() {
        assertTrue(floatTest(EOperator.EQ, 1.0).test(floating(1.0 + 0.5e-6)));
        assertFalse(floatTest(EOperator.EQ, 1.0).test(floating(1.0 + 2e-6)));
        assertTrue(floatTest(EOperator.NE, 1.0).test(floating(1.0 + 2e-6)));
        assertFalse(floatTest(EOperator.NE, 1.0).test(floating(1.0 + 0.5e-6)));
        assertTrue(floatTest(EOperator.IN, 1.0, 2.0).test(floating(2.0000001)));
        assertTrue(floatTest(EOperator.NOT_IN, 1.0, 2.0).test(floating(3.0)));
        assertFalse(floatTest(EOperator.NOT_IN, 1.0, 2.0).test(floating(1.0000001)));
    }

    @Test
    void floatOrderingIsExact() {
        assertTrue(floatTest(EOperator.GT, 1.0).test(floating(1.0000001)));
        assertFalse(floatTest(EOperator.GT, 1.0).test(floating(1.0)));
        assertTrue(floatTest(EOperator.GE, 1.0).test(floating(1.0)));
        assertTrue(floatTest(EOperator.LT, 1.0).test(floating(0.9999999)));
        assertTrue(floatTest(EOperator.LE, 1.0).test(floating(1.0)));
        assertFalse(floatTest(EOperator.LE, 1.0).test(floating(1.0000001)));
    }

    @Test
    void nanMatchesOnlyNegations() {
        assertFalse(floatTest(EOperator.EQ, 1.0).test(floating(Double.NaN)));
        assertFalse(floatTest(EOperator.GT, 1.0).test(floating(Double.NaN)));
        assertTrue(floatTest(EOperator.NE, 1.0).test(floating(Double.NaN)));
    }

    @Test
    void enumsCompareByNumber() {
        RuleValue air = new RuleValue(BigDecimal.ONE, "AIR", null, null);
        RuleValue surface = new RuleValue(BigDecimal.valueOf(2), "SURFACE", null, null);
        FieldValue airValue = enumValue(1, "AIR");

        assertTrue(test(EValueType.ENUM, EOperator.EQ, air).test(airValue));
        assertFalse(test(EValueType.ENUM, EOperator.EQ, surface).test(airValue));
        assertTrue(test(EValueType.ENUM, EOperator.NE, surface).test(airValue));
        assertTrue(test(EValueType.ENUM, EOperator.IN, surface, air).test(airValue));
        assertFalse(test(EValueType.ENUM, EOperator.NOT_IN, surface, air).test(airValue));
        assertTrue(test(EValueType.ENUM, EOperator.NOT_IN, surface).test(airValue));
        assertTrue(test(EValueType.ENUM, EOperator.EQ, new RuleValue(BigDecimal.valueOf(-1), "UNKNOWN", null, null))
                .test(enumValue(-1, "UNKNOWN")), "signed enum values");
    }

    @Test
    void bools() {
        assertTrue(test(EValueType.BOOL, EOperator.EQ, RuleValue.bool(true)).test(bool(true)));
        assertFalse(test(EValueType.BOOL, EOperator.EQ, RuleValue.bool(true)).test(bool(false)));
        assertTrue(test(EValueType.BOOL, EOperator.EQ, RuleValue.bool(false)).test(bool(false)));
    }

    @Test
    void strings() {
        FieldValue unit = text("UNIT-A");

        assertTrue(test(EValueType.STRING, EOperator.EQ, RuleValue.text("UNIT-A")).test(unit));
        assertFalse(test(EValueType.STRING, EOperator.EQ, RuleValue.text("unit-a")).test(unit), "case-sensitive");
        assertTrue(test(EValueType.STRING, EOperator.NE, RuleValue.text("UNIT-B")).test(unit));
        assertTrue(test(EValueType.STRING, EOperator.CONTAINS, RuleValue.text("IT-")).test(unit));
        assertFalse(test(EValueType.STRING, EOperator.CONTAINS, RuleValue.text("X")).test(unit));
        assertTrue(test(EValueType.STRING, EOperator.STARTS_WITH, RuleValue.text("UNIT")).test(unit));
        assertFalse(test(EValueType.STRING, EOperator.STARTS_WITH, RuleValue.text("NIT")).test(unit));
        assertTrue(test(EValueType.STRING, EOperator.IN, RuleValue.text("X"), RuleValue.text("UNIT-A")).test(unit));
        assertTrue(test(EValueType.STRING, EOperator.NOT_IN, RuleValue.text("X"), RuleValue.text("Y")).test(unit));
        assertFalse(test(EValueType.STRING, EOperator.NOT_IN, RuleValue.text("UNIT-A")).test(unit));
    }

    @Test
    void valueOfTheWrongKindNeverMatches() {
        assertFalse(integerTest(EOperator.NE, 5).test(text("5")));
        assertFalse(test(EValueType.STRING, EOperator.NE, RuleValue.text("x")).test(int32(1)));
    }
}
