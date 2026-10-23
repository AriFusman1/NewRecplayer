package com.pcapplayer.extract;

/**
 * Tunables for parsing and reassembly.
 *
 * @param fragmentTimeoutNanos how long an IPv4 fragment set may stay incomplete
 * @param gapTimeoutNanos      how long a TCP hole may stay open before it is declared a capture gap
 * @param udpDuplicateWindowNanos window in which an identical UDP datagram counts as a duplicate capture
 */
public record ExtractionSettings(long fragmentTimeoutNanos, long gapTimeoutNanos, long udpDuplicateWindowNanos) {

    public static ExtractionSettings defaults() {
        return new ExtractionSettings(30_000_000_000L, 2_000_000_000L, 5_000_000L);
    }
}
