package com.pcapplayer.autotag.rules;

import com.pcapplayer.icd.decode.FieldValue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

/**
 * Builds the {@link ValueTest} of a validated predicate. Semantics per type:
 * <ul>
 *   <li><b>Integers</b> (and bitfield bits): exact comparison, also for UINT64 above Long.MAX_VALUE.</li>
 *   <li><b>Floats</b>: {@code EQ}, {@code NE}, {@code IN}, {@code NOT_IN} treat two numbers as equal when
 *       {@code |a − b| ≤ tolerance} (absolute). {@code GT}/{@code GE}/{@code LT}/{@code LE}/{@code BETWEEN}
 *       are exact. NaN matches nothing except {@code NE} and {@code NOT_IN}.</li>
 *   <li><b>Enums</b>: compared by the stored number (the rule keeps the name too, for display).</li>
 *   <li><b>BOOL</b>: {@code EQ} only.</li>
 *   <li><b>Strings</b>: case-sensitive; {@code CONTAINS} and {@code STARTS_WITH} as in Java.</li>
 * </ul>
 * A value of the wrong Java type (which a valid ICD never produces) does not match.
 */
public final class PredicateEvaluator {

    private PredicateEvaluator() {
    }

    public static ValueTest compile(FieldPredicate predicate, EValueType type, double tolerance) {
        List<RuleValue> values = predicate.values();

        return switch (type) {
            case INTEGER, ENUM -> integerTest(predicate.op(), values.stream().map(value -> value.number().toBigIntegerExact()).toList());
            case FLOAT -> floatTest(predicate.op(), values.stream().mapToDouble(value -> value.number().doubleValue()).toArray(), tolerance);
            case BOOL -> {
                boolean expected = values.get(0).bool();
                yield value -> value.value() instanceof Boolean actual && actual == expected;
            }
            case STRING -> stringTest(predicate.op(), values.stream().map(RuleValue::text).toList());
        };
    }

    // ------------------------------------------------------------------ integers

    private static ValueTest integerTest(EOperator op, List<BigInteger> numbers) {
        BigInteger first = numbers.get(0);

        return switch (op) {
            case EQ -> value -> isInteger(value) && compare(value, first) == 0;
            case NE -> value -> isInteger(value) && compare(value, first) != 0;
            case GT -> value -> isInteger(value) && compare(value, first) > 0;
            case GE -> value -> isInteger(value) && compare(value, first) >= 0;
            case LT -> value -> isInteger(value) && compare(value, first) < 0;
            case LE -> value -> isInteger(value) && compare(value, first) <= 0;
            case BETWEEN -> value -> isInteger(value) && compare(value, first) >= 0 && compare(value, numbers.get(1)) <= 0;
            case IN -> value -> isInteger(value) && numbers.stream().anyMatch(number -> compare(value, number) == 0);
            case NOT_IN -> value -> isInteger(value) && numbers.stream().noneMatch(number -> compare(value, number) == 0);
            default -> throw new IllegalArgumentException(op + " is not an integer operator");
        };
    }

    private static boolean isInteger(FieldValue value) {
        return value.value() instanceof Long || value.value() instanceof BigInteger;
    }

    /** Compares without allocating for the usual case (a value that fits a long). */
    static int compare(FieldValue value, BigInteger number) {
        if (value.value() instanceof BigInteger big) return big.compareTo(number);
        long actual = (Long) value.value();
        if (number.bitLength() < Long.SIZE) return Long.compare(actual, number.longValue());
        return number.signum() > 0 ? -1 : 1; // the number is outside the long range
    }

    // ------------------------------------------------------------------ floats

    private static ValueTest floatTest(EOperator op, double[] numbers, double tolerance) {
        double first = numbers[0];

        return switch (op) {
            case EQ -> value -> isFloat(value) && near(value.asDouble(), first, tolerance);
            case NE -> value -> isFloat(value) && !near(value.asDouble(), first, tolerance);
            case GT -> value -> isFloat(value) && value.asDouble() > first;
            case GE -> value -> isFloat(value) && value.asDouble() >= first;
            case LT -> value -> isFloat(value) && value.asDouble() < first;
            case LE -> value -> isFloat(value) && value.asDouble() <= first;
            case BETWEEN -> value -> isFloat(value) && value.asDouble() >= first && value.asDouble() <= numbers[1];
            case IN -> value -> isFloat(value) && anyNear(value.asDouble(), numbers, tolerance);
            case NOT_IN -> value -> isFloat(value) && !anyNear(value.asDouble(), numbers, tolerance);
            default -> throw new IllegalArgumentException(op + " is not a float operator");
        };
    }

    private static boolean isFloat(FieldValue value) {
        return value.value() instanceof Double || value.value() instanceof Float;
    }

    static boolean near(double actual, double expected, double tolerance) {
        return Math.abs(actual - expected) <= tolerance; // false for NaN
    }

    private static boolean anyNear(double actual, double[] numbers, double tolerance) {
        for (double number : numbers) {
            if (near(actual, number, tolerance)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ strings

    private static ValueTest stringTest(EOperator op, List<String> texts) {
        String first = texts.get(0);

        return switch (op) {
            case EQ -> value -> value.value() instanceof String actual && actual.equals(first);
            case NE -> value -> value.value() instanceof String actual && !actual.equals(first);
            case CONTAINS -> value -> value.value() instanceof String actual && actual.contains(first);
            case STARTS_WITH -> value -> value.value() instanceof String actual && actual.startsWith(first);
            case IN -> value -> value.value() instanceof String actual && texts.contains(actual);
            case NOT_IN -> value -> value.value() instanceof String actual && !texts.contains(actual);
            default -> throw new IllegalArgumentException(op + " is not a string operator");
        };
    }

    /** True if the decimal is a whole number (5, 5.0, 5e3), false for 5.5. */
    static boolean isWhole(BigDecimal number) {
        return number.signum() == 0 || number.stripTrailingZeros().scale() <= 0;
    }
}
