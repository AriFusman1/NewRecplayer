package com.pcapplayer.autotag.archive;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * A time span in epoch microseconds (UTC), both ends inclusive.
 * <p>
 * The user types the span in local time; it is converted to UTC instants before any comparison
 * (in the UI, or with {@link #ofLocal} here), so a span across a daylight-saving change still
 * covers exactly the wall-clock interval the user meant.
 */
public record TimeSpan(long startUs, long endUs) {

    public TimeSpan {
        if (startUs > endUs) throw new IllegalArgumentException("The span starts after it ends");
    }

    /** From local date-times in {@code zone} (for example Asia/Jerusalem). */
    public static TimeSpan ofLocal(LocalDateTime start, LocalDateTime end, ZoneId zone) {
        return new TimeSpan(micros(start.atZone(zone).toInstant()), micros(end.atZone(zone).toInstant()));
    }

    public static long micros(Instant instant) {
        return ChronoUnit.MICROS.between(Instant.EPOCH, instant);
    }

    public boolean contains(long us) {
        return us >= startUs && us <= endUs;
    }
}
