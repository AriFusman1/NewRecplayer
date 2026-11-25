package com.pcapplayer.autotag.archive;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeSpanTest {

    private static final ZoneId ISRAEL = ZoneId.of("Asia/Jerusalem");

    private static long us(String instant) {
        return TimeSpan.micros(Instant.parse(instant));
    }

    @Test
    void bothEndsAreInclusive() {
        TimeSpan span = new TimeSpan(100, 200);

        assertTrue(span.contains(100));
        assertTrue(span.contains(200));
        assertFalse(span.contains(99));
        assertFalse(span.contains(201));
        assertThrows(IllegalArgumentException.class, () -> new TimeSpan(2, 1));
    }

    @Test
    void localTimeIsConvertedToUtc() {
        // winter: Israel is UTC+2
        TimeSpan winter = TimeSpan.ofLocal(LocalDateTime.parse("2026-01-10T10:00:00"), LocalDateTime.parse("2026-01-10T12:00:00"), ISRAEL);
        assertEquals(us("2026-01-10T08:00:00Z"), winter.startUs());
        assertEquals(us("2026-01-10T10:00:00Z"), winter.endUs());

        // summer: UTC+3
        TimeSpan summer = TimeSpan.ofLocal(LocalDateTime.parse("2026-07-10T10:00:00"), LocalDateTime.parse("2026-07-10T12:00:00"), ISRAEL);
        assertEquals(us("2026-07-10T07:00:00Z"), summer.startUs());
        assertTrue(summer.contains(us("2026-07-10T09:00:00Z")), "12:00 local is 09:00 UTC, the inclusive end");
        assertFalse(summer.contains(us("2026-07-10T09:00:00.000001Z")));
    }

    @Test
    void spanAcrossTheDaylightSavingChange() {
        // Israel leaves summer time on Sunday 25 Oct 2026 at 02:00 local (back to 01:00)
        TimeSpan span = TimeSpan.ofLocal(LocalDateTime.parse("2026-10-24T22:00:00"), LocalDateTime.parse("2026-10-25T06:00:00"), ISRAEL);

        assertEquals(us("2026-10-24T19:00:00Z"), span.startUs(), "start in summer time (UTC+3)");
        assertEquals(us("2026-10-25T04:00:00Z"), span.endUs(), "end in winter time (UTC+2)");
    }
}
