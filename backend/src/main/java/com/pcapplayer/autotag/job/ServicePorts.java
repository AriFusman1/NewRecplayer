package com.pcapplayer.autotag.job;

import com.pcapplayer.pcapProcessing.capture.CaptureReaders;
import com.pcapplayer.pcapProcessing.decode.L4Packet;
import com.pcapplayer.pcapProcessing.decode.PacketStream;
import com.pcapplayer.pcapProcessing.extract.ExtractionSettings;
import com.pcapplayer.pcapProcessing.script.EConnectionMode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Finds the server ports in a whole capture, so it can be decoded without the user naming ports.
 * <ul>
 *   <li>TCP: the destination port of a SYN (or the source port of a SYN+ACK) is a server port.</li>
 *   <li>A port pair whose connection start was not captured, and every UDP pair, counts its lower
 *       port as the server port (services usually listen on the lower, fixed port).</li>
 * </ul>
 * The record filter of every connection mode uses the server's port: TCP_SERVER and UDP_SERVER
 * filter the destination (the service is the server), TCP_CLIENT filters the source (the source is
 * the server the service connected to).
 */
public final class ServicePorts {

    private static final int PORT_BITS = 16;

    private ServicePorts() {
    }

    public static Set<Integer> find(Path pcap, EConnectionMode mode, ExtractionSettings settings) throws IOException {
        Set<Integer> servers = new HashSet<>();
        Set<Long> pairs = new HashSet<>();

        try (PacketStream in = new PacketStream(CaptureReaders.open(pcap), settings.fragmentTimeoutNanos())) {
            L4Packet packet;

            while ((packet = in.next()) != null) {
                if (packet.tcp() != mode.tcp()) continue;

                if (packet.tcp() && packet.has(L4Packet.SYN)) {
                    servers.add(packet.has(L4Packet.ACK) ? packet.srcPort() : packet.dstPort());
                } else if (packet.payload().length > 0) {
                    pairs.add(pair(packet.srcPort(), packet.dstPort()));
                }
            }
        }

        for (long pair : pairs) {
            int low = (int) (pair >>> PORT_BITS);
            int high = (int) (pair & 0xFFFF);
            if (!servers.contains(low) && !servers.contains(high)) servers.add(low);
        }
        return new TreeSet<>(servers);
    }

    /** The two ports, lower first, in one long. */
    private static long pair(int portA, int portB) {
        return ((long) Math.min(portA, portB) << PORT_BITS) | Math.max(portA, portB);
    }
}
