package com.pcapplayer.extract;

import java.util.List;

/**
 * What a capture file contains, shown after upload.
 *
 * @param endpoints payload-carrying traffic per (protocol, role, port); role "dst" or "src"
 */
public record CaptureSummary(String format, long frames, long startUs, long endUs,
                             long tcpPackets, long udpPackets,
                             List<Endpoint> endpoints, List<Warning> warnings) {

    /**
     * @param ips addresses seen on that side of the port (most active first, capped)
     */
    public record Endpoint(String protocol, String role, int port, long packets, long bytes, List<String> ips) {
    }
}
