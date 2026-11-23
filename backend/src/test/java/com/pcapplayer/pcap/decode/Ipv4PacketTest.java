package com.pcapplayer.pcap.decode;

import com.pcapplayer.pcap.capture.RawFrame;
import org.junit.jupiter.api.Test;
import org.pcap4j.packet.UdpPacket;

import java.util.Arrays;

import static com.pcapplayer.testsupport.Frames.PROTO_UDP;
import static com.pcapplayer.testsupport.Frames.ethernet;
import static com.pcapplayer.testsupport.Frames.ipv4;
import static com.pcapplayer.testsupport.Frames.pattern;
import static com.pcapplayer.testsupport.Frames.udp;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Converting pcap4j IPv4 packets, including the receive-side artifacts the replayer must tolerate. */
class Ipv4PacketTest {

    private static final long TS = 1_700_000_000_123_456_789L;

    @Test
    void convertsHeaderFieldsOfAnUnfragmentedPacket() {
        byte[] udpDatagram = udp(4000, 5000, pattern(30, 1));
        Ipv4Packet packet = decode(ethernet(ipv4("10.1.2.3", "10.250.0.9", PROTO_UDP, udpDatagram, 4242, false, 0, 20 + udpDatagram.length)));

        assertEquals(TS, packet.tsNanos());
        assertEquals("10.1.2.3", Ipv4Packet.ipToString(packet.src()));
        assertEquals("10.250.0.9", Ipv4Packet.ipToString(packet.dst()));
        assertEquals(Ipv4Packet.PROTO_UDP, packet.protocol());
        assertEquals(4242, packet.id());
        assertFalse(packet.isFragment());
        assertArrayEquals(udpDatagram, packet.payload());
        assertFalse(packet.truncated());
        assertInstanceOf(UdpPacket.class, packet.transport(), "pcap4j's decoded UDP packet is kept for reuse");
    }

    @Test
    void trimsEthernetPaddingUsingTheIpTotalLength() {
        byte[] udpDatagram = udp(4000, 5000, pattern(4, 1));
        byte[] padded = concat(ethernet(ipv4("10.0.0.1", "10.0.0.2", PROTO_UDP, udpDatagram)), new byte[6]);

        Ipv4Packet packet = decode(padded);

        assertArrayEquals(udpDatagram, packet.payload());
        assertFalse(packet.truncated());
    }

    @Test
    void usesTheCapturedLengthForGroPacketsWithTotalLengthZero() {
        byte[] udpDatagram = udp(4000, 5000, pattern(3000, 1));
        Ipv4Packet packet = decode(ethernet(ipv4("10.0.0.1", "10.0.0.2", PROTO_UDP, udpDatagram, 1, false, 0, 0)));

        assertEquals(udpDatagram.length, packet.payload().length);
        assertFalse(packet.truncated(), "the frame was captured completely");
    }

    @Test
    void marksPacketsCutBySnaplenAsTruncated() {
        byte[] whole = ethernet(ipv4("10.0.0.1", "10.0.0.2", PROTO_UDP, udp(4000, 5000, pattern(500, 1))));
        byte[] captured = Arrays.copyOf(whole, 200);

        Ipv4Packet packet = Ipv4Packet.from(TS, LinkDecoder.ipv4(new RawFrame(TS, 1, captured, whole.length)), true);

        assertEquals(200 - 14 - 20, packet.payload().length);
        assertTrue(packet.truncated());
    }

    @Test
    void snaplenThatOnlyCutsTheLinkTrailerIsNotTruncation() {
        byte[] ipPacket = ipv4("10.0.0.1", "10.0.0.2", PROTO_UDP, udp(4000, 5000, pattern(10, 1)));
        byte[] captured = ethernet(ipPacket); // the 4-byte FCS after it was not captured

        Ipv4Packet packet = Ipv4Packet.from(TS, LinkDecoder.ipv4(new RawFrame(TS, 1, captured, captured.length + 4)), true);

        assertFalse(packet.truncated());
    }

    @Test
    void convertsFragmentFieldsAndKeepsTheRawPayload() {
        byte[] fragmentPayload = pattern(1480, 9);
        Ipv4Packet first = decode(ethernet(ipv4("10.0.0.1", "10.0.0.2", PROTO_UDP, fragmentPayload, 77, true, 0, 1500)));
        Ipv4Packet middle = decode(ethernet(ipv4("10.0.0.1", "10.0.0.2", PROTO_UDP, fragmentPayload, 77, true, 1480, 1500)));

        assertTrue(first.isFragment());
        assertTrue(first.moreFragments());
        assertEquals(0, first.fragmentOffset());
        assertEquals(1480, middle.fragmentOffset());
        assertArrayEquals(fragmentPayload, middle.payload());
        assertNull(first.transport(), "fragments are decoded only after reassembly");
        assertNull(middle.transport());
    }

    @Test
    void formatsAndParsesAddresses() {
        int address = Ipv4Packet.parseIp(" 192.168.255.1 ");

        assertEquals(0xC0A8FF01, address);
        assertEquals("192.168.255.1", Ipv4Packet.ipToString(address));
        assertEquals("0.0.0.0", Ipv4Packet.ipToString(0));
        assertEquals("255.255.255.255", Ipv4Packet.ipToString(-1));
    }

    @Test
    void rejectsInvalidAddresses() {
        for (String invalid : new String[]{"1.2.3", "1.2.3.256", "a.b.c.d", "1.2.3.4.5", ""}) {
            assertThrows(IllegalArgumentException.class, () -> Ipv4Packet.parseIp(invalid), invalid);
        }
    }

    private static Ipv4Packet decode(byte[] ethernetFrame) {
        return Ipv4Packet.from(TS, LinkDecoder.ipv4(new RawFrame(TS, 1, ethernetFrame, ethernetFrame.length)), false);
    }

    private static byte[] concat(byte[] head, byte[] tail) {
        byte[] result = Arrays.copyOf(head, head.length + tail.length);
        System.arraycopy(tail, 0, result, head.length, tail.length);
        return result;
    }
}
