package com.pcapplayer.icd.timefix;

/**
 * Gives a time-of-day value (LTime, GPS) its date.
 * <p>
 * The value alone could belong to any day. We take the instant with that time of day that is closest
 * to the message's capture time: the same UTC day, the day before or the day after. This is right for
 * any value within ±12 hours of the capture, and handles records that cross midnight: a value of
 * 23:59:59 in a message captured at 00:00:01 belongs to the previous day.
 */
public final class TimeAnchoring {

    private TimeAnchoring() {
    }

    /**
     * @param nanosOfDay     the value as nanoseconds since some UTC midnight
     * @param captureTsNanos epoch nanos of the message's capture
     * @return epoch nanos of the closest instant with that time of day
     */
    public static long anchor(long nanosOfDay, long captureTsNanos) {
        long captureMidnight = Math.floorDiv(captureTsNanos, TimeCodec.NANOS_PER_DAY) * TimeCodec.NANOS_PER_DAY;
        long best = captureMidnight + nanosOfDay;

        for (long dayShift : new long[]{-1, 1}) {
            long candidate = captureMidnight + dayShift * TimeCodec.NANOS_PER_DAY + nanosOfDay;
            if (Math.abs(candidate - captureTsNanos) < Math.abs(best - captureTsNanos)) best = candidate;
        }
        return best;
    }
}
