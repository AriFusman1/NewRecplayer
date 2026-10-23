package com.pcapplayer.extract;

import com.pcapplayer.capture.CaptureReaders;
import com.pcapplayer.net.Ipv4Packet;
import com.pcapplayer.net.L4Packet;
import com.pcapplayer.net.PacketStream;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One streaming pass over a capture to build its {@link CaptureSummary}. */
public final class CaptureScanner {

    private static final int MAX_ENDPOINTS_PER_SIDE = 500;
    private static final int MAX_IPS_PER_ENDPOINT = 50;

    private record Key(String protocol, String role, int port) {
    }

    private static final class Stat {
        long packets, bytes;
        final Map<Integer, Long> ips = new HashMap<>();
    }

    private final ExtractionSettings settings;

    public CaptureScanner(ExtractionSettings settings) {
        this.settings = settings;
    }

    public CaptureSummary scan(Path file) throws IOException {
        Map<Key, Stat> stats = new HashMap<>();
        long tcp = 0, udp = 0, truncated = 0;
        UdpDeduplicator dedup = new UdpDeduplicator(settings.udpDuplicateWindowNanos());
        try (PacketStream in = new PacketStream(CaptureReaders.open(file), settings.fragmentTimeoutNanos())) {
            L4Packet p;
            while ((p = in.next()) != null) {
                String proto = p.tcp() ? "TCP" : "UDP";
                if (p.tcp()) tcp++;
                else {
                    udp++;
                    if (dedup.isDuplicate(p)) continue;
                }
                if (p.truncated()) truncated++;
                if (p.payload().length == 0) continue;
                add(stats, new Key(proto, "dst", p.dstPort()), p.dstIp(), p.payload().length);
                add(stats, new Key(proto, "src", p.srcPort()), p.srcIp(), p.payload().length);
            }
            if (in.frames() == 0) throw new com.pcapplayer.capture.InvalidCaptureException("The capture contains no packets");

            List<Warning> warnings = new ArrayList<>();
            if (truncated > 0) warnings.add(Warning.of("TRUNCATED_PACKETS", "count", truncated));
            if (dedup.duplicates() > 0) warnings.add(Warning.of("UDP_DUPLICATES", "count", dedup.duplicates()));
            if (in.incompleteFragmentSets() > 0) {
                warnings.add(Warning.of("INCOMPLETE_FRAGMENTS", "count", in.incompleteFragmentSets()));
            }
            if (in.endedAbruptly()) warnings.add(Warning.of("CAPTURE_ENDED_ABRUPTLY"));

            return new CaptureSummary(in.format(), in.frames(), in.firstTs() / 1000, in.lastTs() / 1000,
                    tcp, udp, endpoints(stats), warnings);
        }
    }

    private static void add(Map<Key, Stat> stats, Key key, int ip, int bytes) {
        Stat s = stats.computeIfAbsent(key, k -> new Stat());
        s.packets++;
        s.bytes += bytes;
        s.ips.merge(ip, 1L, Long::sum);
    }

    private static List<CaptureSummary.Endpoint> endpoints(Map<Key, Stat> stats) {
        Map<String, List<CaptureSummary.Endpoint>> bySide = new HashMap<>();
        stats.forEach((k, s) -> {
            List<String> ips = s.ips.entrySet().stream()
                    .sorted(Map.Entry.<Integer, Long>comparingByValue().reversed())
                    .limit(MAX_IPS_PER_ENDPOINT)
                    .map(e -> Ipv4Packet.ipToString(e.getKey()))
                    .toList();
            bySide.computeIfAbsent(k.protocol() + k.role(), x -> new ArrayList<>())
                    .add(new CaptureSummary.Endpoint(k.protocol(), k.role(), k.port(), s.packets, s.bytes, ips));
        });
        List<CaptureSummary.Endpoint> out = new ArrayList<>();
        for (List<CaptureSummary.Endpoint> side : bySide.values()) {
            side.sort(Comparator.comparingLong(CaptureSummary.Endpoint::packets).reversed());
            out.addAll(side.subList(0, Math.min(side.size(), MAX_ENDPOINTS_PER_SIDE)));
        }
        return out;
    }
}
