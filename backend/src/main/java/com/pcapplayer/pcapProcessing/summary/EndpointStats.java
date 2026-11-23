package com.pcapplayer.pcapProcessing.summary;

import java.util.HashMap;
import java.util.Map;

/** Running totals for one endpoint (protocol, side, port) while a capture is scanned. */
final class EndpointStats {

    long packets;
    long bytes;
    /** Packets per IPv4 address (as int) seen on this endpoint. */
    final Map<Integer, Long> ips = new HashMap<>();
    final TimelineSeries series;

    EndpointStats(TimelineSeries series) {
        this.series = series;
    }
}
