package com.pcapplayer.pcapProcessing.summary;

import com.pcapplayer.pcapProcessing.capture.CaptureReaders;
import com.pcapplayer.pcapProcessing.capture.InvalidCaptureException;
import com.pcapplayer.pcapProcessing.decode.Ipv4Packet;
import com.pcapplayer.pcapProcessing.decode.L4Packet;
import com.pcapplayer.pcapProcessing.decode.PacketStream;
import com.pcapplayer.pcapProcessing.diagnostics.StreamWarnings;
import com.pcapplayer.pcapProcessing.diagnostics.Warning;
import com.pcapplayer.pcapProcessing.extract.ExtractionSettings;
import com.pcapplayer.pcapProcessing.flow.UdpDeduplicator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** One streaming pass over a capture to build its {@link CaptureSummary}. */
public class CaptureScanner {

    private static final int MAX_ENDPOINTS_PER_SIDE = 500;
    private static final int MAX_IPS_PER_ENDPOINT = 50;

    private record Key(String protocol, String role, int port) {
    }

    private final ExtractionSettings settings;

    public CaptureScanner(ExtractionSettings settings) {
        this.settings = settings;
    }

    public CaptureSummary scan(Path file) throws IOException {
        Map<Key, EndpointStats> stats = new HashMap<>();
        long tcp = 0, udp = 0, truncated = 0;
        UdpDeduplicator dedup = new UdpDeduplicator(settings.udpDuplicateWindowNanos());
        Timeline timeline = null;

        try (PacketStream in = new PacketStream(CaptureReaders.open(file), settings.fragmentTimeoutNanos())) {
            L4Packet packet;

            while ((packet = in.next()) != null) {
                String proto = packet.tcp() ? "TCP" : "UDP";
                if (packet.tcp()) tcp++;
                else {
                    udp++;
                    if (dedup.isDuplicate(packet)) continue;
                }
                if (packet.truncated()) truncated++;
                if (timeline == null) timeline = new Timeline(in.firstTs());
                int timelineBin = timeline.index(packet.tsNanos());
                timeline.count(timelineBin);
                if (packet.payload().length == 0) continue;
                add(stats, timeline, new Key(proto, "dst", packet.dstPort()), packet.dstIp(), packet.payload().length, timelineBin);
                add(stats, timeline, new Key(proto, "src", packet.srcPort()), packet.srcIp(), packet.payload().length, timelineBin);
            }
            if (in.frames() == 0) throw new InvalidCaptureException("The capture contains no packets");

            List<Warning> warnings = new ArrayList<>();
            Warning.addCount(warnings, "TRUNCATED_PACKETS", truncated);
            Warning.addCount(warnings, "UDP_DUPLICATES", dedup.duplicates());
            StreamWarnings.add(in, warnings);

            return new CaptureSummary(in.format(), in.frames(), TimeUnit.NANOSECONDS.toMicros(in.firstTs()), TimeUnit.NANOSECONDS.toMicros(in.lastTs()),
                    tcp, udp, endpoints(stats), warnings,
                    timeline == null ? 0 : timeline.binUs(), timeline == null ? new long[0] : timeline.counts());
        }
    }

    private static void add(Map<Key, EndpointStats> stats, Timeline timeline, Key key, int ip, int bytes, int timelineBin) {
        EndpointStats stat = stats.computeIfAbsent(key, newKey -> new EndpointStats(timeline.newSeries()));
        stat.series.add(timelineBin);
        stat.packets++;
        stat.bytes += bytes;
        stat.ips.merge(ip, 1L, Long::sum);
    }

    private static List<CaptureSummary.Endpoint> endpoints(Map<Key, EndpointStats> stats) {
        Map<String, List<CaptureSummary.Endpoint>> bySide = new HashMap<>();

        stats.forEach((key, stat) -> {
            List<String> ips = stat.ips.entrySet().stream()
                    .sorted(Map.Entry.<Integer, Long>comparingByValue().reversed())
                    .limit(MAX_IPS_PER_ENDPOINT)
                    .map(e -> Ipv4Packet.ipToString(e.getKey()))
                    .toList();
            bySide.computeIfAbsent(key.protocol() + key.role(), side -> new ArrayList<>())
                    .add(new CaptureSummary.Endpoint(key.protocol(), key.role(), key.port(), stat.packets, stat.bytes, ips,
                            stat.series.from(), stat.series.counts()));
        });
        List<CaptureSummary.Endpoint> out = new ArrayList<>();

        for (List<CaptureSummary.Endpoint> side : bySide.values()) {
            side.sort(Comparator.comparingLong(CaptureSummary.Endpoint::packets).reversed());
            out.addAll(side.subList(0, Math.min(side.size(), MAX_ENDPOINTS_PER_SIDE)));
        }
        return out;
    }
}
