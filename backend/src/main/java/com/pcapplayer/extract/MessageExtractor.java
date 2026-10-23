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

/**
 * Builds the replay script for one record: selects the source's data with the filter, reassembles
 * it (IP fragments → UDP datagrams / TCP streams) and turns it into timed steps.
 * Range filtering happens after IP reassembly, so a datagram counts at its last fragment's time.
 */
public final class MessageExtractor {

    private static final int MAX_GAP_DETAILS = 20;

    private final ExtractionSettings settings;

    public MessageExtractor(ExtractionSettings settings) {
        this.settings = settings;
    }

    public ExtractionResult extract(Path file, ExtractionFilter filter) throws IOException {
        try (PacketStream in = new PacketStream(CaptureReaders.open(file), settings.fragmentTimeoutNanos())) {
            Collector c = filter.mode().tcp() ? new TcpCollector(filter) : new UdpCollector(filter);
            long otherProtocol = 0;
            L4Packet p;
            while ((p = in.next()) != null) {
                if (!filter.inRange(p.tsNanos())) continue;
                if (p.tcp() != filter.mode().tcp()) {
                    if (filter.forward(p) && p.payload().length > 0) otherProtocol++;
                    continue;
                }
                c.accept(p);
            }
            List<Warning> warnings = new ArrayList<>();
            ExtractionResult r = c.finish(otherProtocol, warnings);
            if (in.incompleteFragmentSets() > 0) {
                warnings.add(Warning.of("INCOMPLETE_FRAGMENTS", "count", in.incompleteFragmentSets()));
            }
            if (in.endedAbruptly()) {
                warnings.add(Warning.of("CAPTURE_ENDED_ABRUPTLY"));
            }
            return r;
        }
    }

    private interface Collector {
        void accept(L4Packet p);

        ExtractionResult finish(long otherProtocol, List<Warning> warnings);
    }

    private long offset(ExtractionFilter f, long ts) {
        return Math.max(0, ts - f.startNanos());
    }

    // ---------------------------------------------------------------- UDP

    private final class UdpCollector implements Collector {
        private final ExtractionFilter f;
        private final UdpDeduplicator dedup = new UdpDeduplicator(settings.udpDuplicateWindowNanos());
        private final List<ScriptStep> steps = new ArrayList<>();
        private long truncated;
        private long bytes;
        private long first = Long.MAX_VALUE, last = Long.MIN_VALUE;

        UdpCollector(ExtractionFilter f) {
            this.f = f;
        }

        @Override
        public void accept(L4Packet p) {
            if (!f.forward(p)) return;
            if (p.truncated()) {
                truncated++; // never send a datagram the capture cut short
                return;
            }
            if (dedup.isDuplicate(p)) return;
            steps.add(new ScriptStep.Send(offset(f, p.tsNanos()), p.payload()));
            bytes += p.payload().length;
            first = Math.min(first, p.tsNanos());
            last = Math.max(last, p.tsNanos());
        }

        @Override
        public ExtractionResult finish(long otherProtocol, List<Warning> warnings) {
            steps.sort(Comparator.comparingLong(ScriptStep::offsetNanos)); // stable: keeps capture order on ties
            if (truncated > 0) warnings.add(Warning.of("TRUNCATED_PACKETS", "count", truncated));
            if (dedup.duplicates() > 0) warnings.add(Warning.of("UDP_DUPLICATES", "count", dedup.duplicates()));
            ReplayScript script = new ReplayScript(f.mode(), steps, steps.size(), bytes, f.endNanos() - f.startNanos());
            return new ExtractionResult(script, steps.isEmpty() ? 0 : first, steps.isEmpty() ? 0 : last,
                    0, otherProtocol, warnings);
        }
    }

    // ---------------------------------------------------------------- TCP

    /** Forward-oriented 4-tuple: source endpoint → service endpoint. */
    private record FlowKey(int srcIp, int srcPort, int dstIp, int dstPort) {
        String describe() {
            return Ipv4Packet.ipToString(srcIp) + ":" + srcPort + " → " + Ipv4Packet.ipToString(dstIp) + ":" + dstPort;
        }
    }

    private final class TcpConn {
        final FlowKey key;
        final TcpStreamReassembler stream = new TcpStreamReassembler(settings.gapTimeoutNanos());
        final long startTs;
        long fwdSynSeq = -1, revSynSeq = -1;
        long finFwdTs = -1, finRevTs = -1, rstTs = -1;
        long lastTs;

        TcpConn(FlowKey key, long startTs) {
            this.key = key;
            this.startTs = startTs;
            this.lastTs = startTs;
        }

        boolean closed() {
            return rstTs >= 0 || (finFwdTs >= 0 && finRevTs >= 0);
        }

        long closeTs() {
            return rstTs >= 0 ? rstTs : Math.max(finFwdTs, finRevTs);
        }

        boolean seenClose() {
            return rstTs >= 0 || finFwdTs >= 0 || finRevTs >= 0;
        }

        long lastChunkTs() {
            List<TcpStreamReassembler.Chunk> c = stream.chunks();
            return c.isEmpty() ? startTs : c.get(c.size() - 1).tsNanos();
        }
    }

    private final class TcpCollector implements Collector {
        private final ExtractionFilter f;
        private final Map<FlowKey, TcpConn> active = new HashMap<>();
        private final List<TcpConn> all = new ArrayList<>();
        private long truncated;

        TcpCollector(ExtractionFilter f) {
            this.f = f;
        }

        @Override
        public void accept(L4Packet p) {
            boolean fwd = f.forward(p);
            boolean rev = !fwd && f.reverse(p);
            if (!fwd && !rev) return;
            FlowKey key = fwd
                    ? new FlowKey(p.srcIp(), p.srcPort(), p.dstIp(), p.dstPort())
                    : new FlowKey(p.dstIp(), p.dstPort(), p.srcIp(), p.srcPort());
            TcpConn conn = active.get(key);

            if (p.has(L4Packet.SYN)) {
                long prevSyn = conn == null ? -1 : (fwd ? conn.fwdSynSeq : conn.revSynSeq);
                boolean newConnection = conn == null
                        || (prevSyn >= 0 && prevSyn != p.seq())            // different ISN on the same tuple
                        || (prevSyn < 0 && (conn.seenClose() || conn.stream.bytes() > 0));
                if (newConnection) {
                    conn = open(key, p.tsNanos());
                }
                if (fwd) {
                    conn.fwdSynSeq = p.seq();
                    conn.stream.syn(p.tsNanos(), p.seq());
                } else {
                    conn.revSynSeq = p.seq();
                }
            } else if (conn == null) {
                if (!fwd) return; // service-side packets alone don't start a connection
                conn = open(key, p.tsNanos());
            } else if (conn.closed() && fwd && p.payload().length > 0 && p.tsNanos() > conn.closeTs()) {
                conn = open(key, p.tsNanos()); // tuple reused without a SYN in range
            }
            conn.lastTs = Math.max(conn.lastTs, p.tsNanos());

            if (fwd) {
                if (p.truncated()) truncated++;
                conn.stream.segment(p.tsNanos(), p.seq(), p.payload());
                if (p.has(L4Packet.FIN) && conn.finFwdTs < 0) conn.finFwdTs = p.tsNanos();
            } else {
                if (p.has(L4Packet.ACK)) conn.stream.ack(p.tsNanos(), p.ack());
                if (p.has(L4Packet.FIN) && conn.finRevTs < 0) conn.finRevTs = p.tsNanos();
            }
            if (p.has(L4Packet.RST) && conn.rstTs < 0) conn.rstTs = p.tsNanos();
        }

        private TcpConn open(FlowKey key, long ts) {
            TcpConn c = new TcpConn(key, ts);
            active.put(key, c);
            all.add(c);
            return c;
        }

        @Override
        public ExtractionResult finish(long otherProtocol, List<Warning> warnings) {
            List<TcpConn> withData = new ArrayList<>();
            List<Map<String, Object>> gapDetails = new ArrayList<>();
            long gapCount = 0, gapBytes = 0;
            for (TcpConn c : all) {
                c.stream.finish();
                for (TcpStreamReassembler.Gap g : c.stream.gaps()) {
                    gapCount++;
                    gapBytes += g.bytes();
                    if (gapDetails.size() < MAX_GAP_DETAILS) {
                        gapDetails.add(Map.of("tsUs", g.tsNanos() / 1000, "bytes", g.bytes(), "connection", c.key.describe()));
                    }
                }
                if (!c.stream.chunks().isEmpty()) withData.add(c);
            }
            withData.sort(Comparator.comparingLong(c -> c.startTs));

            // Group connections whose lifetimes overlap; between groups the replay closes and reconnects.
            List<ScriptStep> steps = new ArrayList<>();
            long bytes = 0, messages = 0, overlapping = 0;
            long first = Long.MAX_VALUE, last = Long.MIN_VALUE;
            long prevCloseAt = -1; // close offset of the previous group, -1 before the first group
            int i = 0;
            while (i < withData.size()) {
                List<ScriptStep> merged = new ArrayList<>();
                long groupEnd = Long.MIN_VALUE;
                boolean groupClosed = false;
                int j = i;
                do {
                    TcpConn c = withData.get(j);
                    for (TcpStreamReassembler.Gap g : c.stream.gaps()) {
                        merged.add(new ScriptStep.Gap(offset(f, g.tsNanos()), g.bytes())); // before same-time data
                    }
                    for (TcpStreamReassembler.Chunk ch : c.stream.chunks()) {
                        merged.add(new ScriptStep.Send(offset(f, ch.tsNanos()), ch.data()));
                    }
                    long end = c.closed() ? Math.max(c.closeTs(), c.lastChunkTs()) : c.lastChunkTs();
                    if (end >= groupEnd) {
                        groupEnd = end;
                        groupClosed = c.closed();
                    }
                    j++;
                } while (j < withData.size() && withData.get(j).startTs < groupEnd);
                if (j - i > 1) overlapping += j - i;

                if (prevCloseAt >= 0) {
                    long connect = offset(f, withData.get(i).startTs);
                    steps.add(new ScriptStep.Reconnect(Math.min(prevCloseAt, connect), connect));
                }
                merged.sort(Comparator.comparingLong(ScriptStep::offsetNanos)); // stable
                for (ScriptStep st : merged) {
                    steps.add(st);
                    if (st instanceof ScriptStep.Send send) {
                        bytes += send.data().length;
                        messages++;
                        first = Math.min(first, f.startNanos() + send.offsetNanos());
                        last = Math.max(last, f.startNanos() + send.offsetNanos());
                    }
                }
                // No FIN/RST seen: the connection is closed when the next one opens.
                prevCloseAt = groupClosed ? offset(f, groupEnd) : Long.MAX_VALUE;
                i = j;
            }

            if (truncated > 0) warnings.add(Warning.of("TRUNCATED_PACKETS", "count", truncated));
            if (gapCount > 0) {
                warnings.add(Warning.of("TCP_GAPS", "count", gapCount, "bytes", gapBytes, "details", gapDetails));
            }
            if (overlapping > 0) warnings.add(Warning.of("OVERLAPPING_CONNECTIONS", "count", overlapping));

            ReplayScript script = new ReplayScript(f.mode(), steps, messages, bytes, f.endNanos() - f.startNanos());
            return new ExtractionResult(script, messages == 0 ? 0 : first, messages == 0 ? 0 : last,
                    withData.size(), otherProtocol, warnings);
        }
    }
}
