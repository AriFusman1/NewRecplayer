package com.pcapplayer.icd.timefix;

/** How a field stores a time. */
public enum ETimeKind {

    /** Unix epoch time in seconds (UTC). */
    REGULAR_SECONDS(TimeCodec.NANOS_PER_SECOND, false),

    /** Unix epoch time in milliseconds (UTC). */
    REGULAR_MILLIS(TimeCodec.NANOS_PER_MILLI, false),

    /** Seconds since the last UTC midnight. */
    LTIME(TimeCodec.NANOS_PER_SECOND, true),

    /** 25 µs ticks since the last UTC midnight. */
    GPS(TimeCodec.NANOS_PER_GPS_TICK, true);

    private final long unitNanos;
    private final boolean timeOfDay;

    ETimeKind(long unitNanos, boolean timeOfDay) {
        this.unitNanos = unitNanos;
        this.timeOfDay = timeOfDay;
    }

    /** Nanoseconds in one unit of the stored value. */
    public long unitNanos() {
        return unitNanos;
    }

    /** True if the value is a time of day only (no date): LTIME and GPS. */
    public boolean timeOfDay() {
        return timeOfDay;
    }
}
