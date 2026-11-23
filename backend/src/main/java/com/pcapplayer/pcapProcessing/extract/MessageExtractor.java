package com.pcapplayer.pcapProcessing.extract;

import com.pcapplayer.pcapProcessing.capture.CaptureReaders;
import com.pcapplayer.pcapProcessing.decode.L4Packet;
import com.pcapplayer.pcapProcessing.decode.PacketStream;
import com.pcapplayer.pcapProcessing.diagnostics.StreamWarnings;
import com.pcapplayer.pcapProcessing.diagnostics.Warning;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the replay script for one record: selects the source's data with the filter, reassembles
 * it (IP fragments → UDP datagrams / TCP streams) and turns it into timed steps.
 * Range filtering happens after IP reassembly, so a datagram counts at its last fragment's time.
 */
public class MessageExtractor {

    /** Gathers the selected packets of one protocol and turns them into the replay script. */
    interface Collector {
        void accept(L4Packet packet);

        ExtractionResult finish(long otherProtocol, List<Warning> warnings);
    }

    private final ExtractionSettings settings;

    public MessageExtractor(ExtractionSettings settings) {
        this.settings = settings;
    }

    public ExtractionResult extract(Path file, ExtractionFilter filter) throws IOException {
        try (PacketStream in = new PacketStream(CaptureReaders.open(file), settings.fragmentTimeoutNanos())) {
            Collector collector = newCollector(filter);
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

    private Collector newCollector(ExtractionFilter filter) {
        return filter.mode().tcp()
                ? new TcpMessageCollector(filter, settings.gapTimeoutNanos())
                : new UdpMessageCollector(filter, settings.udpDuplicateWindowNanos());
    }
}
