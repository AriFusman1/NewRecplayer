package com.pcapplayer.extract;

import com.pcapplayer.net.L4Packet;

import java.util.Set;

/**
 * Selects the source's data.
 *
 * @param ip         optional IPv4 address (as int) on the same endpoint as the port filter; null = all IPs
 * @param startNanos range start (inclusive), epoch nanos
 * @param endNanos   range end (inclusive), epoch nanos
 */
public record ExtractionFilter(ConnectionMode mode, Set<Integer> ports, Integer ip, long startNanos, long endNanos) {

    public boolean inRange(long ts) {
        return ts >= startNanos && ts <= endNanos;
    }

    /** Packet flows from the source to the service (protocol not checked). */
    public boolean forward(L4Packet p) {
        return mode.filterOnDestination()
                ? ports.contains(p.dstPort()) && (ip == null || ip == p.dstIp())
                : ports.contains(p.srcPort()) && (ip == null || ip == p.srcIp());
    }

    /** Packet flows from the service back to the source (protocol not checked). */
    public boolean reverse(L4Packet p) {
        return mode.filterOnDestination()
                ? ports.contains(p.srcPort()) && (ip == null || ip == p.srcIp())
                : ports.contains(p.dstPort()) && (ip == null || ip == p.dstIp());
    }
}
