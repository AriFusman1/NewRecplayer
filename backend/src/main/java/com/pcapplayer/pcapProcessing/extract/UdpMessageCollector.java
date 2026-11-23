package com.pcapplayer.pcapProcessing.extract;

import com.pcapplayer.pcapProcessing.decode.L4Packet;
import com.pcapplayer.pcapProcessing.diagnostics.Warning;
import com.pcapplayer.pcapProcessing.flow.UdpDeduplicator;
import com.pcapplayer.pcapProcessing.script.ReplayScript;
import com.pcapplayer.pcapProcessing.script.ScriptStep;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** UDP: every forward datagram (minus truncated ones and capture duplicates) becomes one send. */
final class UdpMessageCollector implements MessageExtractor.Collector {

    private final ExtractionFilter filter;
    private final UdpDeduplicator dedup;
    private final List<ScriptStep> steps = new ArrayList<>();
    private long truncated;
    private long bytes;
    private long first = Long.MAX_VALUE;
    private long last = Long.MIN_VALUE;

    UdpMessageCollector(ExtractionFilter filter, long duplicateWindowNanos) {
        this.filter = filter;
        this.dedup = new UdpDeduplicator(duplicateWindowNanos);
    }

    @Override
    public void accept(L4Packet packet) {
        if (!filter.forward(packet)) return;

        if (packet.truncated()) {
            truncated++; // never send a datagram the capture cut short
            return;
        }
        if (dedup.isDuplicate(packet)) return;
        steps.add(new ScriptStep.Send(filter.offsetNanos(packet.tsNanos()), packet.payload()));
        bytes += packet.payload().length;
        first = Math.min(first, packet.tsNanos());
        last = Math.max(last, packet.tsNanos());
    }

    @Override
    public ExtractionResult finish(long otherProtocol, List<Warning> warnings) {
        steps.sort(Comparator.comparingLong(ScriptStep::offsetNanos)); // stable: keeps capture order on ties
        Warning.addCount(warnings, "TRUNCATED_PACKETS", truncated);
        Warning.addCount(warnings, "UDP_DUPLICATES", dedup.duplicates());
        ReplayScript script = new ReplayScript(filter.mode(), steps, steps.size(), bytes, filter.durationNanos());
        boolean empty = steps.isEmpty();
        return new ExtractionResult(script, empty ? 0 : first, empty ? 0 : last, 0, otherProtocol, warnings);
    }
}
