package com.pcapplayer.icd.timefix;

import com.pcapplayer.icd.decode.FieldValue;
import com.pcapplayer.icd.decode.IcdBytes;
import com.pcapplayer.icd.model.EFieldKind;
import com.pcapplayer.icd.model.EFieldType;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static com.pcapplayer.icd.timefix.ETimeKind.GPS;
import static com.pcapplayer.icd.timefix.ETimeKind.LTIME;
import static com.pcapplayer.icd.timefix.ETimeKind.REGULAR_MILLIS;
import static com.pcapplayer.icd.timefix.ETimeKind.REGULAR_SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Conversions between stored values and instants, worked out by hand. */
class TimeCodecTest {

    /** 2023-11-14T22:13:20.5Z: epoch second 1 700 000 000.5, 80 000.5 s after UTC midnight. */
    static final long T = Instant.parse("2023-11-14T22:13:20.500Z").getEpochSecond() * 1_000_000_000L + 500_000_000L;
    static final long DAY = TimeCodec.NANOS_PER_DAY;
    static final long SECOND = TimeCodec.NANOS_PER_SECOND;
    static final long MIDNIGHT = Math.floorDiv(T, DAY) * DAY;

    // ------------------------------------------------------------------ instant → value, per kind and type

    @Test
    void regularSeconds() {
        assertEncoded(1_700_000_000L, T, REGULAR_SECONDS, EFieldType.INT64);
        assertEncoded(1_700_000_000L, T, REGULAR_SECONDS, EFieldType.UINT32);
        assertEncoded(1_700_000_000L, T, REGULAR_SECONDS, EFieldType.INT32);
        assertEncoded(1_700_000_000L, T, REGULAR_SECONDS, EFieldType.UINT64);
        assertEquals(1_700_000_000.5, decodeDouble(TimeCodec.encode(T, REGULAR_SECONDS, EFieldType.FLOAT64, 0), EFieldType.FLOAT64),
                "FLOAT64 keeps the fraction");
        assertClamped(Short.MAX_VALUE, T, REGULAR_SECONDS, EFieldType.INT16);
    }

    @Test
    void regularMillis() {
        assertEncoded(1_700_000_000_500L, T, REGULAR_MILLIS, EFieldType.INT64);
        assertEncoded(1_700_000_000_500L, T, REGULAR_MILLIS, EFieldType.UINT64);
        assertEquals(1_700_000_000_500.25, decodeDouble(TimeCodec.encode(T + 250_000, REGULAR_MILLIS, EFieldType.FLOAT64, 0),
                EFieldType.FLOAT64));
        assertClamped(Integer.MAX_VALUE, T, REGULAR_MILLIS, EFieldType.INT32);
        assertClamped(4_294_967_295L, T, REGULAR_MILLIS, EFieldType.UINT32);
    }

    @Test
    void ltime() {
        assertEncoded(80_000L, T, LTIME, EFieldType.UINT32);
        assertEncoded(80_000L, T, LTIME, EFieldType.INT32);
        assertEncoded(80_000L, T, LTIME, EFieldType.INT64);
        assertEquals(80_000.5, decodeDouble(TimeCodec.encode(T, LTIME, EFieldType.FLOAT64, 0), EFieldType.FLOAT64));
        assertEquals(80_000.5, decodeDouble(TimeCodec.encode(T, LTIME, EFieldType.FLOAT32, 0), EFieldType.FLOAT32));
        assertClamped(65_535L, T, LTIME, EFieldType.UINT16);
        assertClamped(127L, T, LTIME, EFieldType.INT8);
        assertEncoded(80_000L + 1, T + 999_999_999L, LTIME, EFieldType.UINT32); // floor, not round
    }

    @Test
    void gps() {
        long ticks = 80_000_500_000_000L / 25_000; // 3 200 020 000
        assertEncoded(ticks, T, GPS, EFieldType.UINT32);
        assertEncoded(ticks, T, GPS, EFieldType.INT64);
        assertEncoded(ticks, T, GPS, EFieldType.UINT64);
        assertEquals(ticks + 0.5, decodeDouble(TimeCodec.encode(T + 12_500, GPS, EFieldType.FLOAT64, 0), EFieldType.FLOAT64));
        assertClamped(Integer.MAX_VALUE, T, GPS, EFieldType.INT32);
        assertEquals(new EncodedValue(4095, true), TimeCodec.encode(T, GPS, EFieldType.UINT32, 12), "a 12-bit bit field");
        assertEncoded(ticks, T + 24_999, GPS, EFieldType.UINT32); // floor to the tick
    }

    @Test
    void timeOfDayWrapsAtUtcMidnight() {
        assertEncoded(0L, MIDNIGHT + DAY, LTIME, EFieldType.UINT32);
        assertEncoded(86_399L, MIDNIGHT + DAY - 1, LTIME, EFieldType.UINT32);
        assertEncoded(2L, MIDNIGHT + DAY + 50_000, GPS, EFieldType.UINT32);
    }

    // ------------------------------------------------------------------ value → instant

    @Test
    void valuesBecomeInstants() throws InvalidTimeException {
        assertEquals(T - SECOND / 2, TimeCodec.toInstant(number(EFieldType.UINT32, 1_700_000_000L), REGULAR_SECONDS, T));
        assertEquals(T, TimeCodec.toInstant(number(EFieldType.FLOAT64, 1_700_000_000.5), REGULAR_SECONDS, T));
        assertEquals(T, TimeCodec.toInstant(number(EFieldType.INT64, 1_700_000_000_500L), REGULAR_MILLIS, T));
        assertEquals(T, TimeCodec.toInstant(number(EFieldType.FLOAT64, 80_000.5), LTIME, T));
        assertEquals(T - SECOND / 2, TimeCodec.toInstant(number(EFieldType.UINT32, 80_000L), LTIME, T));
        assertEquals(T, TimeCodec.toInstant(number(EFieldType.UINT32, 3_200_020_000L), GPS, T));
        assertEquals(T, TimeCodec.toInstant(number(EFieldType.FLOAT32, 80_000.5), LTIME, T));
    }

    @Test
    void everyKindRoundTripsThroughEveryWideEnoughType() throws InvalidTimeException {
        EFieldType[] types = {EFieldType.INT64, EFieldType.UINT64, EFieldType.FLOAT64};

        for (ETimeKind kind : ETimeKind.values()) {
            for (EFieldType type : types) {
                long instant = T - T % kind.unitNanos(); // a whole number of units, so integers are exact
                EncodedValue encoded = TimeCodec.encode(instant, kind, type, 0);
                FieldValue value = new FieldValue(EFieldKind.PRIMITIVE, type, IcdBytes.toValue(encoded.raw(), type), null);
                assertEquals(instant, TimeCodec.toInstant(value, kind, T + 3 * SECOND), kind + " in " + type);
            }
        }
    }

    @Test
    void notATime() {
        FieldValue text = new FieldValue(EFieldKind.STRING, null, "noon", null);
        assertThrows(InvalidTimeException.class, () -> TimeCodec.toInstant(text, REGULAR_SECONDS, T));
        assertThrows(InvalidTimeException.class, () -> TimeCodec.toInstant(number(EFieldType.INT64, Long.MAX_VALUE), REGULAR_MILLIS, T));
        assertThrows(InvalidTimeException.class, () -> TimeCodec.toInstant(number(EFieldType.FLOAT64, Double.NaN), LTIME, T));
    }

    // ------------------------------------------------------------------ anchoring

    @Test
    void timeOfDayIsAnchoredToTheClosestDay() {
        long justAfterMidnight = MIDNIGHT + SECOND;
        long justBeforeMidnight = MIDNIGHT + DAY - SECOND;

        assertEquals(MIDNIGHT - SECOND, TimeAnchoring.anchor(DAY - SECOND, justAfterMidnight),
                "23:59:59 captured at 00:00:01 is the previous day");
        assertEquals(MIDNIGHT + DAY + SECOND, TimeAnchoring.anchor(SECOND, justBeforeMidnight),
                "00:00:01 captured at 23:59:59 is the next day");
        assertEquals(MIDNIGHT + 11 * 3600 * SECOND, TimeAnchoring.anchor(11 * 3600 * SECOND, MIDNIGHT + 12 * 3600 * SECOND),
                "the same day");
    }

    // ------------------------------------------------------------------ helpers

    private static void assertEncoded(long expected, long instant, ETimeKind kind, EFieldType type) {
        EncodedValue encoded = TimeCodec.encode(instant, kind, type, 0);
        assertFalse(encoded.clamped(), kind + " in " + type + " should fit");
        assertEquals(expected, ((Number) IcdBytes.toValue(encoded.raw(), type)).longValue(), kind + " in " + type);
    }

    private static void assertClamped(long expected, long instant, ETimeKind kind, EFieldType type) {
        EncodedValue encoded = TimeCodec.encode(instant, kind, type, 0);
        assertTrue(encoded.clamped(), kind + " in " + type + " should be clamped");
        assertEquals(expected, encoded.raw());
    }

    private static double decodeDouble(EncodedValue encoded, EFieldType type) {
        return (Double) IcdBytes.toValue(encoded.raw(), type);
    }

    static FieldValue number(EFieldType type, Number value) {
        Object stored = type.floating() ? (Object) value.doubleValue() : (Object) value.longValue();
        return new FieldValue(EFieldKind.PRIMITIVE, type, stored, null);
    }
}
