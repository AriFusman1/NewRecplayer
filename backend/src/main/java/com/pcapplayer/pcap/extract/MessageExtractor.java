package com.pcapplayer.pcap.extract;

import com.pcapplayer.pcap.capture.CaptureReaders;
import com.pcapplayer.pcap.decode.Ipv4Packet;
import com.pcapplayer.pcap.decode.L4Packet;
import com.pcapplayer.pcap.decode.PacketStream;
import com.pcapplayer.pcap.diagnostics.StreamWarnings;
import com.pcapplayer.pcap.diagnostics.Warning;
import com.pcapplayer.pcap.flow.TcpStreamReassembler;
import com.pcapplayer.pcap.flow.UdpDeduplicator;
import com.pcapplayer.pcap.script.ReplayScript;
import com.pcapplayer.pcap.script.ScriptStep;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Builds the replay script for one record: selects the source's data with the filter, reassembles
 * it (IP fragments → UDP datagrams / TCP streams) and turns it into timed steps.
 * Range filtering happens after IP reassembly, so a datagram counts at its last fragment's time.
 */
public class MessageExtractor {

    private static final int MAX_GAP_DETAILS = 20;

    private final ExtractionSettings settings;

    public MessageExtractor(ExtractionSettings settings) {
        this.settings = settings;
    }

    public ExtractionResult extract(Path file, ExtractionFilter filter) throws IOException {
        try (PacketStream in = new PacketStream(CaptureReaders.open(file), settings.fragmentTimeoutNanos())) {
            Collector collector = filter.mode().tcp() ? new TcpCollector(filter) : new UdpCollector(filter);
            long otherProtocol = 0;
            L4Packet packet;
            while ((packet = in.next()) != null) {
                if (!filter.inRange(packet.tsNanos())) continue;
                if (packet.tcp() != filter.mode().tcp()) {
                    if (filter.forward(packet) && packet.payload().length > 0) otherProtocol++;
                    continue;
                }
                collector.accept(packet);
            }
            List<Warning> warnings = new ArrayList<>();
            ExtractionResult result = collector.finish(otherProtocol, warnings);
            StreamWarnings.add(in, warnings);
            return result;
        }
    }

    private interface Collector {
        void accept(L4Packet packet);

        ExtractionResult finish(long otherProtocol, List<Warning> warnings);
    }

    private long offset(ExtractionFilter filter, long tsNanos) {
        return Math.max(0, tsNanos - filter.startNanos());
    }

    // ---------------------------------------------------------------- UDP

    private class UdpCollector implements Collector {
        private final ExtractionFilter filter;
        private final UdpDeduplicator dedup = new UdpDeduplicator(settings.udpDuplicateWindowNanos());
        private final List<ScriptStep> steps = new ArrayList<>();
        private long truncated;
        private long bytes;
        private long first = Long.MAX_VALUE, last = Long.MIN_VALUE;

        UdpCollector(ExtractionFilter filter) {
            this.filter = filter;
        }

        @Override
        public void accept(L4Packet packet) {
            if (!filter.forward(packet)) return;
            if (packet.truncated()) {
                truncated++; // never send a datagram the capture cut short
                return;
            }
            if (dedup.isDuplicate(packet)) return;
            steps.add(new ScriptStep.Send(offset(filter, packet.tsNanos()), packet.payload()));
            bytes += packet.payload().length;
            first = Math.min(first, packet.tsNanos());
            last = Math.max(last, packet.tsNanos());
        }

        @Override
        public ExtractionResult finish(long otherProtocol, List<Warning> warnings) {
            steps.sort(Comparator.comparingLong(ScriptStep::offsetNanos)); // stable: keeps capture order on ties
            Warning.addCount(warnings, "TRUNCATED_PACKETS", truncated);
            Warning.addCount(warnings, "UDP_DUPLICATES", dedup.duplicates());
            ReplayScript script = new ReplayScript(filter.mode(), steps, steps.size(), bytes, filter.endNanos() - filter.startNanos());
            return new ExtractionResult(script, steps.isEmpty() ? 0 : first, steps.isEmpty() ? 0 : last,
                    0, otherProtocol, warnings);
        }
    }

    // ---------------------------------------------------------------- TCP

    /** Forward-oriented 4-tuple: source endpoint → service endpoint. */
    private record FlowKey(int srcIp, int srcPort, int dstIp, int dstPort) {

        static FlowKey of(L4Packet packet, boolean forward) {
            return forward
                    ? new FlowKey(packet.srcIp(), packet.srcPort(), packet.dstIp(), packet.dstPort())
                    : new FlowKey(packet.dstIp(), packet.dstPort(), packet.srcIp(), packet.srcPort());
        }

        String describe() {
            return Ipv4Packet.ipToString(srcIp) + ":" + srcPort + " → " + Ipv4Packet.ipToString(dstIp) + ":" + dstPort;
        }
    }

    /** One recorded TCP connection; timestamps are epoch nanos, -1 while not seen. */
    private class TcpConn {
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
            List<TcpStreamReassembler.Chunk> chunks = stream.chunks();
            return chunks.isEmpty() ? startTs : chunks.get(chunks.size() - 1).tsNanos();
        }

        /** Until when the connection counts as alive: its close, or (never closed) its last data. */
        long endTs() {
            return closed() ? Math.max(closeTs(), lastChunkTs()) : lastChunkTs();
        }

        long synSeq(boolean forward) {
            return forward ? fwdSynSeq : revSynSeq;
        }
    }

    /** Connections whose lifetimes overlap; the replay sends them over one connection. */
    private record ConnGroup(List<TcpConn> members, long endTs, boolean closed) {

        TcpConn first() {
            return members.get(0);
        }
    }

    /** TCP_GAPS totals, with details for the first {@link #MAX_GAP_DETAILS} gaps. */
    private static final class GapReport {
        private long count;
        private long bytes;
        private final List<Map<String, Object>> details = new ArrayList<>();

        void add(TcpStreamReassembler.Gap gap, FlowKey connection) {
            count++;
            bytes += gap.bytes();
            if (details.size() < MAX_GAP_DETAILS) {
                details.add(Map.of("tsUs", TimeUnit.NANOSECONDS.toMicros(gap.tsNanos()), "bytes", gap.bytes(),
                        "connection", connection.describe()));
            }
        }

        void addTo(List<Warning> warnings) {
            if (count > 0) warnings.add(Warning.of("TCP_GAPS", "count", count, "bytes", bytes, "details", details));
        }
    }

    /** The script being built, with running message totals. */
    private final class ScriptSteps {
        private final ExtractionFilter filter;
        final List<ScriptStep> steps = new ArrayList<>();
        long bytes;
        long messages;
        long first = Long.MAX_VALUE;
        long last = Long.MIN_VALUE;

        ScriptSteps(ExtractionFilter filter) {
            this.filter = filter;
        }

        void add(ScriptStep step) {
            steps.add(step);
            if (step instanceof ScriptStep.Send send) {
                long ts = filter.startNanos() + send.offsetNanos();
                bytes += send.data().length;
                messages++;
                first = Math.min(first, ts);
                last = Math.max(last, ts);
            }
        }
    }

    private class TcpCollector implements Collector {
        private final ExtractionFilter filter;
        private final Map<FlowKey, TcpConn> active = new HashMap<>();
        private final List<TcpConn> all = new ArrayList<>();
        private long truncated;

        TcpCollector(ExtractionFilter filter) {
            this.filter = filter;
        }

        @Override
        public void accept(L4Packet packet) {
            boolean forward = filter.forward(packet);
            if (!forward && !filter.reverse(packet)) return;
            TcpConn connection = connectionOf(packet, forward);
            if (connection == null) return;
            connection.lastTs = Math.max(connection.lastTs, packet.tsNanos());
            if (forward) acceptForward(connection, packet);
            else acceptReverse(connection, packet);
            if (packet.has(L4Packet.RST) && connection.rstTs < 0) connection.rstTs = packet.tsNanos();
        }

        /** The connection the packet belongs to, opening one if the packet starts it; null to ignore the packet. */
        private TcpConn connectionOf(L4Packet packet, boolean forward) {
            FlowKey key = FlowKey.of(packet, forward);
            TcpConn connection = active.get(key);
            if (packet.has(L4Packet.SYN)) {
                if (synStartsNewConnection(connection, packet, forward)) connection = open(key, packet.tsNanos());
                recordSyn(connection, packet, forward);
                return connection;
            }
            if (connection == null) {
                return forward ? open(key, packet.tsNanos()) : null; // service-side packets alone don't start one
            }
            if (reusedWithoutSyn(connection, packet, forward)) return open(key, packet.tsNanos());
            return connection;
        }

        private boolean synStartsNewConnection(TcpConn connection, L4Packet syn, boolean forward) {
            if (connection == null) return true;
            long previousSyn = connection.synSeq(forward);
            if (previousSyn >= 0) return previousSyn != syn.seq(); // different ISN on the same tuple
            return connection.seenClose() || connection.stream.bytes() > 0;
        }

        private void recordSyn(TcpConn connection, L4Packet syn, boolean forward) {
            if (forward) {
                connection.fwdSynSeq = syn.seq();
                connection.stream.syn(syn.tsNanos(), syn.seq());
            } else {
                connection.revSynSeq = syn.seq();
            }
        }

        /** New data on the tuple of a connection that already closed: a new one whose SYN is not in range. */
        private boolean reusedWithoutSyn(TcpConn connection, L4Packet packet, boolean forward) {
            return forward && connection.closed() && packet.payload().length > 0 && packet.tsNanos() > connection.closeTs();
        }

        private void acceptForward(TcpConn connection, L4Packet packet) {
            if (packet.truncated()) truncated++;
            connection.stream.segment(packet.tsNanos(), packet.seq(), packet.payload());
            if (packet.has(L4Packet.FIN) && connection.finFwdTs < 0) connection.finFwdTs = packet.tsNanos();
        }

        private void acceptReverse(TcpConn connection, L4Packet packet) {
            if (packet.has(L4Packet.ACK)) connection.stream.ack(packet.tsNanos(), packet.ack());
            if (packet.has(L4Packet.FIN) && connection.finRevTs < 0) connection.finRevTs = packet.tsNanos();
        }

        private TcpConn open(FlowKey key, long tsNanos) {
            TcpConn connection = new TcpConn(key, tsNanos);
            active.put(key, connection);
            all.add(connection);
            return connection;
        }

        @Override
        public ExtractionResult finish(long otherProtocol, List<Warning> warnings) {
            GapReport gaps = new GapReport();
            List<TcpConn> withData = new ArrayList<>();
            for (TcpConn connection : all) {
                connection.stream.finish();
                connection.stream.gaps().forEach(gap -> gaps.add(gap, connection.key));
                if (!connection.stream.chunks().isEmpty()) withData.add(connection);
            }
            withData.sort(Comparator.comparingLong(connection -> connection.startTs));

            List<ConnGroup> groups = groupOverlapping(withData);
            ScriptSteps script = buildSteps(groups);
            long overlapping = groups.stream().mapToLong(group -> group.members().size()).filter(size -> size > 1).sum();

            Warning.addCount(warnings, "TRUNCATED_PACKETS", truncated);
            gaps.addTo(warnings);
            Warning.addCount(warnings, "OVERLAPPING_CONNECTIONS", overlapping);

            ReplayScript replayScript = new ReplayScript(filter.mode(), script.steps, script.messages, script.bytes,
                    filter.endNanos() - filter.startNanos());
            boolean empty = script.messages == 0;
            return new ExtractionResult(replayScript, empty ? 0 : script.first, empty ? 0 : script.last,
                    withData.size(), otherProtocol, warnings);
        }

        /** Splits connections (sorted by start) into groups whose lifetimes overlap. */
        private List<ConnGroup> groupOverlapping(List<TcpConn> sorted) {
            List<ConnGroup> groups = new ArrayList<>();
            int next = 0;
            while (next < sorted.size()) {
                List<TcpConn> members = new ArrayList<>();
                long groupEnd = Long.MIN_VALUE;
                boolean groupClosed = false;
                do {
                    TcpConn connection = sorted.get(next++);
                    members.add(connection);
                    if (connection.endTs() >= groupEnd) {
                        groupEnd = connection.endTs();
                        groupClosed = connection.closed();
                    }
                } while (next < sorted.size() && sorted.get(next).startTs < groupEnd);
                groups.add(new ConnGroup(members, groupEnd, groupClosed));
            }
            return groups;
        }

        /** Each group's steps in time order; between groups the replay closes and reconnects. */
        private ScriptSteps buildSteps(List<ConnGroup> groups) {
            ScriptSteps script = new ScriptSteps(filter);
            long previousCloseAt = -1; // -1 before the first group
            for (ConnGroup group : groups) {
                if (previousCloseAt >= 0) {
                    long connectAt = offset(filter, group.first().startTs);
                    script.add(new ScriptStep.Reconnect(Math.min(previousCloseAt, connectAt), connectAt));
                }
                stepsOf(group).forEach(script::add);
                // No FIN/RST seen: the connection is closed when the next one opens.
                previousCloseAt = group.closed() ? offset(filter, group.endTs()) : Long.MAX_VALUE;
            }
            return script;
        }

        /** The group's gap markers and data, merged in time order (a gap goes before data at the same time). */
        private List<ScriptStep> stepsOf(ConnGroup group) {
            List<ScriptStep> merged = new ArrayList<>();
            for (TcpConn connection : group.members()) {
                for (TcpStreamReassembler.Gap gap : connection.stream.gaps()) {
                    merged.add(new ScriptStep.Gap(offset(filter, gap.tsNanos()), gap.bytes()));
                }
                for (TcpStreamReassembler.Chunk chunk : connection.stream.chunks()) {
                    merged.add(new ScriptStep.Send(offset(filter, chunk.tsNanos()), chunk.data()));
                }
            }
            merged.sort(Comparator.comparingLong(ScriptStep::offsetNanos)); // stable
            return merged;
        }
    }
}
