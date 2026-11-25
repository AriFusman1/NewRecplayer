package com.pcapplayer.pcapProcessing.extract;

import com.pcapplayer.pcapProcessing.decode.Ipv4Packet;
import com.pcapplayer.pcapProcessing.decode.L4Packet;
import com.pcapplayer.pcapProcessing.flow.TcpStreamReassembler;

import java.util.List;

/** One recorded TCP connection; timestamps are epoch nanos, -1 while not seen. */
final class TcpConnection {

    /** Forward-oriented 4-tuple: source endpoint → service endpoint. */
    record FlowKey(int srcIp, int srcPort, int dstIp, int dstPort) {

        static FlowKey of(L4Packet packet, boolean forward) {
            return forward
                    ? new FlowKey(packet.srcIp(), packet.srcPort(), packet.dstIp(), packet.dstPort())
                    : new FlowKey(packet.dstIp(), packet.dstPort(), packet.srcIp(), packet.srcPort());
        }

        String describe() {
            return Ipv4Packet.ipToString(srcIp) + ":" + srcPort + " → " + Ipv4Packet.ipToString(dstIp) + ":" + dstPort;
        }
    }

    final FlowKey key;
    final TcpStreamReassembler stream;
    final long startTs;
    long fwdSynSeq = -1;
    long revSynSeq = -1;
    long finFwdTs = -1;
    long finRevTs = -1;
    long rstTs = -1;
    long lastTs;

    /** Index of this connection's flow in the script; set when the script is built. */
    int flowId = -1;

    TcpConnection(FlowKey key, long startTs, long gapTimeoutNanos) {
        this.key = key;
        this.startTs = startTs;
        this.lastTs = startTs;
        this.stream = new TcpStreamReassembler(gapTimeoutNanos);
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
