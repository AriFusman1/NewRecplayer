package com.pcapplayer.pcap.summary;

import com.pcapplayer.pcap.diagnostics.Warning;

import java.util.List;

/**
 * What the upload screen shows about a capture. Times are epoch microseconds.
 * <p>
 * {@code timeline[i]} counts the TCP/UDP packets in {@code [startUs + i*binUs, startUs + (i+1)*binUs)}.
 * Summaries stored before the timeline existed have {@code binUs == 0} and no timeline.
 */
public record CaptureSummary(String format, long frames, long startUs, long endUs,
                             long tcpPackets, long udpPackets,
                             List<Endpoint> endpoints, List<Warning> warnings,
                             long binUs, long[] timeline) {

    /**
     * @param ips      addresses seen on that side of the port (most active first, capped)
     * @param binFrom  first timeline bucket this endpoint has packets in
     * @param counts   packets with payload per bucket, starting at {@code binFrom}
     */
    public record Endpoint(String protocol, String role, int port, long packets, long bytes, List<String> ips,
                           int binFrom, int[] counts) {
    }
}
