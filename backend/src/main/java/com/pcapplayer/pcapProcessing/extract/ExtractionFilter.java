package com.pcapplayer.pcapProcessing.extract;

import com.pcapplayer.pcapProcessing.decode.L4Packet;
import com.pcapplayer.pcapProcessing.script.EConnectionMode;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Selects the source's data.
 *
 * @param ip         optional IPv4 address (as int) on the same endpoint as the port filter; null = all IPs
 * @param startNanos range start (inclusive), epoch nanos
 * @param endNanos   range end (inclusive), epoch nanos
 */
public record ExtractionFilter(EConnectionMode mode, Set<Integer> ports, Integer ip, long startNanos, long endNanos) {

    private static final long NANOS_PER_MICRO = 1000;

    /**
     * From a record's microsecond range; the end microsecond is inclusive, so the filter runs
     * through its last nanosecond.
     */
    public static ExtractionFilter ofMicros(EConnectionMode mode, Collection<Integer> ports, Integer ip,
                                            long startUs, long endUs) {
        return new ExtractionFilter(mode, new HashSet<>(ports), ip,
                startUs * NANOS_PER_MICRO, endUs * NANOS_PER_MICRO + NANOS_PER_MICRO - 1);
    }

    public boolean inRange(long tsNanos) {
        return tsNanos >= startNanos && tsNanos <= endNanos;
    }

    /** Nanoseconds from the range start; 0 for anything earlier. */
    public long offsetNanos(long tsNanos) {
        return Math.max(0, tsNanos - startNanos);
    }

    public long durationNanos() {
        return endNanos - startNanos;
    }

    /** Packet flows from the source to the service (protocol not checked). */
    public boolean forward(L4Packet packet) {
        return mode.filterOnDestination()
                ? ports.contains(packet.dstPort()) && (ip == null || ip == packet.dstIp())
                : ports.contains(packet.srcPort()) && (ip == null || ip == packet.srcIp());
    }

    /** Packet flows from the service back to the source (protocol not checked). */
    public boolean reverse(L4Packet packet) {
        return mode.filterOnDestination()
                ? ports.contains(packet.srcPort()) && (ip == null || ip == packet.srcIp())
                : ports.contains(packet.dstPort()) && (ip == null || ip == packet.dstIp());
    }
}
