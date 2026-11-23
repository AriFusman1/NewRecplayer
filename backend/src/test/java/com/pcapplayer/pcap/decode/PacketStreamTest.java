package com.pcapplayer.pcap.decode;

import com.pcapplayer.pcap.capture.CaptureReaders;
import com.pcapplayer.testsupport.CaptureFiles;
import com.pcapplayer.testsupport.CaptureFiles.Frame;
import com.pcapplayer.testsupport.CaptureFiles.InterfaceFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.pcapplayer.testsupport.CaptureFiles.LINKTYPE_ETHERNET;
import static com.pcapplayer.testsupport.Frames.ACK;
import static com.pcapplayer.testsupport.Frames.ETHERTYPE_ARP;
import static com.pcapplayer.testsupport.Frames.ETHERTYPE_VLAN;
import static com.pcapplayer.testsupport.Frames.PROTO_TCP;
import static com.pcapplayer.testsupport.Frames.PROTO_UDP;
import static com.pcapplayer.testsupport.Frames.PSH;
import static com.pcapplayer.testsupport.Frames.SYN;
import static com.pcapplayer.testsupport.Frames.ethernet;
import static com.pcapplayer.testsupport.Frames.ipv4;
import static com.pcapplayer.testsupport.Frames.pattern;
import static com.pcapplayer.testsupport.Frames.tcp;
import static com.pcapplayer.testsupport.Frames.udp;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The whole pipeline: capture file → libpcap → pcap4j → reassembly → TCP/UDP packets (needs libpcap). */
class PacketStreamTest {

    private static final long T0 = 1_700_000_000_000_000_000L;
    private static final long MS = 1_000_000L;
    private static final long FRAGMENT_TIMEOUT = 30_000 * MS;
    private static final String CLIENT = "10.0.0.1", SERVER = "10.0.0.2";

    @TempDir
    Path dir;

    @BeforeEach
    void requireLibpcap() throws IOException {
        CaptureFiles.assumeLibpcap(dir);
    }

    @Test
    void decodesTcpAndUdpAndSkipsEverythingElse() throws IOException {
        byte[] request = "hello".getBytes();
        Path file = CaptureFiles.writePcap(dir.resolve("mixed.pcap"), LINKTYPE_ETHERNET, List.of(
                new Frame(T0, ethernet(ipv4(CLIENT, SERVER, PROTO_TCP, tcp(50000, 6000, 1000, 0, SYN, new byte[0])))),
                new Frame(T0 + MS, ethernet(ETHERTYPE_ARP, new byte[28])),
                new Frame(T0 + 2 * MS, ethernet(ipv4(CLIENT, SERVER, PROTO_TCP, tcp(50000, 6000, 1001, 1, PSH | ACK, request)), ETHERTYPE_VLAN)),
                new Frame(T0 + 3 * MS, ethernet(ipv4(CLIENT, SERVER, 1, new byte[8]))), // ICMP
                new Frame(T0 + 4 * MS, ethernet(ipv4(CLIENT, SERVER, PROTO_UDP, udp(40000, 5000, pattern(20, 1)))))));

        try (PacketStream stream = new PacketStream(CaptureReaders.open(file), FRAGMENT_TIMEOUT)) {
            List<L4Packet> packets = readAll(stream);

            assertEquals(3, packets.size());
            assertTrue(packets.get(0).has(L4Packet.SYN));
            assertArrayEquals(request, packets.get(1).payload());
            assertEquals(T0 + 2 * MS, packets.get(1).tsNanos());
            assertFalse(packets.get(2).tcp());
            assertEquals(5, stream.frames(), "counters cover every frame, not only TCP/UDP");
            assertEquals(T0, stream.firstTs());
            assertEquals(T0 + 4 * MS, stream.lastTs());
            assertEquals("pcap", stream.format());
            assertFalse(stream.endedAbruptly());
        }
    }

    @Test
    void reassemblesOutOfOrderFragmentsAndCountsIncompleteSets() throws IOException {
        byte[] data = pattern(3000, 2);
        byte[] datagram = udp(40000, 5000, data);
        byte[] part1 = Arrays.copyOfRange(datagram, 0, 1480);
        byte[] part2 = Arrays.copyOfRange(datagram, 1480, 2960);
        byte[] part3 = Arrays.copyOfRange(datagram, 2960, datagram.length);
        Path file = CaptureFiles.writePcapng(dir.resolve("fragments.pcapng"), List.of(LINKTYPE_ETHERNET), 9, List.of(
                new InterfaceFrame(0, T0 + 10 * MS, ethernet(ipv4(CLIENT, SERVER, PROTO_UDP, part3, 7, false, 2960, 20 + part3.length))),
                new InterfaceFrame(0, T0 + 11 * MS, ethernet(ipv4(CLIENT, SERVER, PROTO_UDP, part1, 7, true, 0, 1500))),
                new InterfaceFrame(0, T0 + 12 * MS, ethernet(ipv4(CLIENT, SERVER, PROTO_UDP, part2, 7, true, 1480, 1500))),
                // only the first fragment of another datagram: never completes
                new InterfaceFrame(0, T0 + 13 * MS, ethernet(ipv4(CLIENT, SERVER, PROTO_UDP, part1, 8, true, 0, 1500)))));

        try (PacketStream stream = new PacketStream(CaptureReaders.open(file), FRAGMENT_TIMEOUT)) {
            List<L4Packet> packets = readAll(stream);

            assertEquals(1, packets.size());
            L4Packet reassembled = packets.get(0);
            assertArrayEquals(data, reassembled.payload());
            assertEquals(7, reassembled.ipId());
            assertEquals(T0 + 12 * MS, reassembled.tsNanos(), "timestamp of the fragment that completed the set");
            assertEquals(1, stream.incompleteFragmentSets());
            assertEquals("pcapng", stream.format());
        }
    }

    @Test
    void decodesGroSuperPacketsWithIpTotalLengthZero() throws IOException {
        byte[] data = pattern(30000, 11);
        Path file = CaptureFiles.writePcap(dir.resolve("gro.pcap"), LINKTYPE_ETHERNET, List.of(
                new Frame(T0, ethernet(ipv4(CLIENT, SERVER, PROTO_TCP, tcp(50000, 6000, 1, 1, PSH | ACK, data), 1, false, 0, 0)))));

        try (PacketStream stream = new PacketStream(CaptureReaders.open(file), FRAGMENT_TIMEOUT)) {
            L4Packet packet = stream.next();

            assertArrayEquals(data, packet.payload());
            assertFalse(packet.truncated());
        }
    }

    private static List<L4Packet> readAll(PacketStream stream) throws IOException {
        List<L4Packet> packets = new ArrayList<>();
        L4Packet packet;
        while ((packet = stream.next()) != null) packets.add(packet);
        return packets;
    }
}
