package com.pcapplayer.pcapProcessing.extract;

import com.pcapplayer.pcapProcessing.decode.L4Packet;
import com.pcapplayer.pcapProcessing.diagnostics.Warning;
import com.pcapplayer.pcapProcessing.extract.TcpConnection.FlowKey;
import com.pcapplayer.pcapProcessing.flow.TcpStreamReassembler;
import com.pcapplayer.pcapProcessing.script.ReplayScript;
import com.pcapplayer.pcapProcessing.script.ScriptStep;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * TCP: rebuilds each connection's forward stream, then lays the streams out in time. Connections
 * that overlap share one replay connection; between them the replay closes and reconnects.
 */
final class TcpMessageCollector implements MessageExtractor.Collector {

    /** Connections whose lifetimes overlap; the replay sends them over one connection. */
    private record ConnGroup(List<TcpConnection> members, long endTs, boolean closed) {

        TcpConnection first() {
            return members.get(0);
        }
    }

    private final ExtractionFilter filter;
    private final long gapTimeoutNanos;
    private final Map<FlowKey, TcpConnection> active = new HashMap<>();
    private final List<TcpConnection> all = new ArrayList<>();
    private long truncated;

    TcpMessageCollector(ExtractionFilter filter, long gapTimeoutNanos) {
        this.filter = filter;
        this.gapTimeoutNanos = gapTimeoutNanos;
    }

    @Override
    public void accept(L4Packet packet) {
        boolean forward = filter.forward(packet);
        if (!forward && !filter.reverse(packet)) return;

        TcpConnection connection = connectionOf(packet, forward);
        if (connection == null) return;

        connection.lastTs = Math.max(connection.lastTs, packet.tsNanos());
        if (forward) acceptForward(connection, packet);
        else acceptReverse(connection, packet);
        if (packet.has(L4Packet.RST) && connection.rstTs < 0) connection.rstTs = packet.tsNanos();
    }

    /** The connection the packet belongs to, opening one if the packet starts it; null to ignore the packet. */
    private TcpConnection connectionOf(L4Packet packet, boolean forward) {
        FlowKey key = FlowKey.of(packet, forward);
        TcpConnection connection = active.get(key);

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

    private boolean synStartsNewConnection(TcpConnection connection, L4Packet syn, boolean forward) {
        if (connection == null) return true;

        long previousSyn = connection.synSeq(forward);
        if (previousSyn >= 0) return previousSyn != syn.seq(); // different ISN on the same tuple
        return connection.seenClose() || connection.stream.bytes() > 0;
    }

    private void recordSyn(TcpConnection connection, L4Packet syn, boolean forward) {
        if (forward) {
            connection.fwdSynSeq = syn.seq();
            connection.stream.syn(syn.tsNanos(), syn.seq());
        } else {
            connection.revSynSeq = syn.seq();
        }
    }

    /** New data on the tuple of a connection that already closed: a new one whose SYN is not in range. */
    private boolean reusedWithoutSyn(TcpConnection connection, L4Packet packet, boolean forward) {
        return forward && connection.closed() && packet.payload().length > 0 && packet.tsNanos() > connection.closeTs();
    }

    private void acceptForward(TcpConnection connection, L4Packet packet) {
        if (packet.truncated()) truncated++;
        connection.stream.segment(packet.tsNanos(), packet.seq(), packet.payload());
        if (packet.has(L4Packet.FIN) && connection.finFwdTs < 0) connection.finFwdTs = packet.tsNanos();
    }

    private void acceptReverse(TcpConnection connection, L4Packet packet) {
        if (packet.has(L4Packet.ACK)) connection.stream.ack(packet.tsNanos(), packet.ack());
        if (packet.has(L4Packet.FIN) && connection.finRevTs < 0) connection.finRevTs = packet.tsNanos();
    }

    private TcpConnection open(FlowKey key, long tsNanos) {
        TcpConnection connection = new TcpConnection(key, tsNanos, gapTimeoutNanos);
        active.put(key, connection);
        all.add(connection);
        return connection;
    }

    @Override
    public ExtractionResult finish(long otherProtocol, List<Warning> warnings) {
        TcpGapReport gaps = new TcpGapReport();
        List<TcpConnection> withData = new ArrayList<>();

        for (TcpConnection connection : all) {
            connection.stream.finish();
            connection.stream.gaps().forEach(gap -> gaps.add(gap, connection.key));
            if (!connection.stream.chunks().isEmpty()) withData.add(connection);
        }
        withData.sort(Comparator.comparingLong(connection -> connection.startTs));

        List<ConnGroup> groups = groupOverlapping(withData);
        ScriptStepsBuilder script = buildSteps(groups);
        long overlapping = groups.stream().mapToLong(group -> group.members().size()).filter(size -> size > 1).sum();

        Warning.addCount(warnings, "TRUNCATED_PACKETS", truncated);
        gaps.addTo(warnings);
        Warning.addCount(warnings, "OVERLAPPING_CONNECTIONS", overlapping);

        ReplayScript replayScript = new ReplayScript(filter.mode(), script.steps, script.messages, script.bytes,
                filter.durationNanos());
        boolean empty = script.messages == 0;
        return new ExtractionResult(replayScript, empty ? 0 : script.first, empty ? 0 : script.last,
                withData.size(), otherProtocol, warnings);
    }

    /** Splits connections (sorted by start) into groups whose lifetimes overlap. */
    private List<ConnGroup> groupOverlapping(List<TcpConnection> sorted) {
        List<ConnGroup> groups = new ArrayList<>();
        int next = 0;

        while (next < sorted.size()) {
            List<TcpConnection> members = new ArrayList<>();
            long groupEnd = Long.MIN_VALUE;
            boolean groupClosed = false;

            do {
                TcpConnection connection = sorted.get(next++);
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
    private ScriptStepsBuilder buildSteps(List<ConnGroup> groups) {
        ScriptStepsBuilder script = new ScriptStepsBuilder(filter);
        long previousCloseAt = -1; // -1 before the first group

        for (ConnGroup group : groups) {
            if (previousCloseAt >= 0) {
                long connectAt = filter.offsetNanos(group.first().startTs);
                script.add(new ScriptStep.Reconnect(Math.min(previousCloseAt, connectAt), connectAt));
            }
            stepsOf(group).forEach(script::add);
            // No FIN/RST seen: the connection is closed when the next one opens.
            previousCloseAt = group.closed() ? filter.offsetNanos(group.endTs()) : Long.MAX_VALUE;
        }
        return script;
    }

    /** The group's gap markers and data, merged in time order (a gap goes before data at the same time). */
    private List<ScriptStep> stepsOf(ConnGroup group) {
        List<ScriptStep> merged = new ArrayList<>();

        for (TcpConnection connection : group.members()) {
            for (TcpStreamReassembler.Gap gap : connection.stream.gaps()) {
                merged.add(new ScriptStep.Gap(filter.offsetNanos(gap.tsNanos()), gap.bytes()));
            }

            for (TcpStreamReassembler.Chunk chunk : connection.stream.chunks()) {
                merged.add(new ScriptStep.Send(filter.offsetNanos(chunk.tsNanos()), chunk.data()));
            }
        }
        merged.sort(Comparator.comparingLong(ScriptStep::offsetNanos)); // stable
        return merged;
    }
}
