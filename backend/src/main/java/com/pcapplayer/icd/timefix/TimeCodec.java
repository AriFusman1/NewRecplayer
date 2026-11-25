package com.pcapplayer.icd.timefix;

import com.pcapplayer.icd.decode.FieldValue;
import com.pcapplayer.icd.model.EFieldType;

/**
 * Converts between stored time values and instants (epoch nanoseconds, UTC), for every time kind and
 * every numeric field type.
 * <pre>
 * kind             value → instant                         instant → value
 * REGULAR_SECONDS  v · 10⁹ ns since the epoch              ns / 10⁹
 * REGULAR_MILLIS   v · 10⁶ ns since the epoch              ns / 10⁶
 * LTIME            anchor(v · 10⁹ ns of the day)            (ns of the UTC day) / 10⁹
 * GPS              anchor(v · 25 000 ns of the day)         (ns of the UTC day) / 25 000
 * </pre>
 * Integer fields are rounded down (floor); float fields keep the fraction. A value that does not fit the
 * field's type is clamped. Time-of-day kinds are anchored to a date by {@link TimeAnchoring}.
 */
public final class TimeCodec {

    public static final long NANOS_PER_SECOND = 1_000_000_000L;
    public static final long NANOS_PER_MILLI = 1_000_000L;
    public static final long NANOS_PER_GPS_TICK = 25_000L;
    public static final long NANOS_PER_DAY = 86_400L * NANOS_PER_SECOND;

    /** Doubles beyond this (in ns) are not times; well inside the long range. */
    private static final double MAX_NANOS = 9.0e18;

    private TimeCodec() {
    }

    /**
     * The instant a stored value stands for.
     *
     * @param captureTsNanos when the message was captured: the date that time-of-day values are anchored to
     */
    public static long toInstant(FieldValue value, ETimeKind kind, long captureTsNanos) throws InvalidTimeException {
        long nanos = toNanos(value, kind.unitNanos());
        return kind.timeOfDay() ? TimeAnchoring.anchor(nanos, captureTsNanos) : nanos;
    }

    /** {@code value × unitNanos}, rounded to whole nanoseconds for floats. */
    static long toNanos(FieldValue value, long unitNanos) throws InvalidTimeException {
        if (!value.numeric()) throw new InvalidTimeException("not a number: " + value.value());

        if (value.value() instanceof Double number) {
            double nanos = number * unitNanos;
            if (!Double.isFinite(nanos) || Math.abs(nanos) > MAX_NANOS) throw new InvalidTimeException("out of range: " + number);
            return Math.round(nanos);
        }
        if (!(value.value() instanceof Long number)) throw new InvalidTimeException("out of range: " + value.value());

        try {
            return Math.multiplyExact(number, unitNanos);
        } catch (ArithmeticException e) {
            throw new InvalidTimeException("out of range: " + number);
        }
    }

    /**
     * The value to store for {@code instantNanos} in a field of {@code type} holding {@code kind}.
     *
     * @param bitWidth for a bit field, its width (the value is unsigned and limited to it); 0 otherwise
     */
    public static EncodedValue encode(long instantNanos, ETimeKind kind, EFieldType type, int bitWidth) {
        // time-of-day kinds count from the UTC midnight of the instant's own day, so wrap-around is natural
        long nanos = kind.timeOfDay() ? Math.floorMod(instantNanos, NANOS_PER_DAY) : instantNanos;
        long unit = kind.unitNanos();
        long whole = Math.floorDiv(nanos, unit);

        if (type.floating()) {
            double value = whole + (double) Math.floorMod(nanos, unit) / unit; // keeps the fraction
            long raw = type == EFieldType.FLOAT32 ? Float.floatToIntBits((float) value) & 0xFFFF_FFFFL : Double.doubleToLongBits(value);
            return new EncodedValue(raw, false);
        }
        long min = bitWidth > 0 ? 0 : type.minValue();
        long max = bitWidth > 0 ? maxOfBits(bitWidth) : type.maxValue();
        if (whole < min) return new EncodedValue(min, true);
        if (whole > max) return new EncodedValue(max, true);
        return new EncodedValue(whole, false);
    }

    private static long maxOfBits(int bits) {
        return bits >= 63 ? Long.MAX_VALUE : (1L << bits) - 1;
    }
}
