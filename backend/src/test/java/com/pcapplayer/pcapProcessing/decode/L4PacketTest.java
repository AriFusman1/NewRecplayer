package com.pcapplayer.pcapProcessing.decode;

import com.pcapplayer.pcapProcessing.capture.RawFrame;
import org.junit.jupiter.api.Test;

import static com.pcapplayer.testSupport.Frames.ACK;
import static com.pcapplayer.testSupport.Frames.FIN;
import static com.pcapplayer.testSupport.Frames.PROTO_TCP;
import static com.pcapplayer.testSupport.Frames.PROTO_UDP;
import static com.pcapplayer.testSupport.Frames.PSH;
import static com.pcapplayer.testSupport.Frames.SYN;
import static com.pcapplayer.testSupport.Frames.ethernet;
import static com.pcapplayer.testSupport.Frames.ipv4;
import static com.pcapplayer.testSupport.Frames.pattern;
import static com.pcapplayer.testSupport.Frames.tcp;
import static com.pcapplayer.testSupport.Frames.udp;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TCP/UDP decoding with pcap4j, for directly decoded and for reassembled packets. */
class L4PacketTest {

    private static final long TS = 1_700_000_000_000_000_000L;

    @Test
    void decodesTcpHeaderFieldsAndPayload() {
        byte[] data = "GET / HTTP/1.1\r\n\r\n".getBytes();
        long seqAboveSignedRange = 0xFFFFFFF0L;
        L4Packet packet = L4Packet.decode(fromFrame(ipv4("10.0.0.1", "10.0.0.2", PROTO_TCP,
                tcp(40000, 80, seqAboveSignedRange, 5001, PSH | ACK, data))));

        assertNotNull(packet);
        assertTrue(packet.tcp());
        assertEquals(40000, packet.srcPort());
        assertEquals(80, packet.dstPort());
        assertEquals(Ipv4Packet.parseIp("10.0.0.1"), packet.srcIp());
        assertEquals(seqAboveSignedRange, packet.seq(), "sequence number is unsigned");
        assertEquals(5001, packet.ack());
        assertTrue(packet.has(L4Packet.PSH) && packet.has(L4Packet.ACK));
        assertFalse(packet.has(L4Packet.SYN) || packet.has(L4Packet.FIN) || packet.has(L4Packet.RST));
        assertArrayEquals(data, packet.payload());
        assertEquals(TS, packet.tsNanos());
    }

    @Test
    void mapsEveryTcpFlag() {
        L4Packet synAck = L4Packet.decode(fromFrame(ipv4("10.0.0.2", "10.0.0.1", PROTO_TCP, tcp(80, 40000, 1, 2, SYN | ACK, new byte[0]))));
        L4Packet fin = L4Packet.decode(fromFrame(ipv4("10.0.0.2", "10.0.0.1", PROTO_TCP, tcp(80, 40000, 1, 2, FIN, new byte[0]))));

        assertEquals(L4Packet.SYN | L4Packet.ACK, synAck.flags());
        assertEquals(L4Packet.FIN, fin.flags());
        assertEquals(0, synAck.payload().length);
    }

    @Test
    void decodesUdpHeaderFieldsAndPayload() {
        byte[] data = pattern(100, 5);
        L4Packet packet = L4Packet.decode(fromFrame(ipv4("10.1.1.1", "10.1.1.2", PROTO_UDP, udp(40000, 5000, data))));

        assertNotNull(packet);
        assertFalse(packet.tcp());
        assertEquals(40000, packet.srcPort());
        assertEquals(5000, packet.dstPort());
        assertArrayEquals(data, packet.payload());
        assertFalse(packet.truncated());
        assertEquals(0, packet.seq());
        assertEquals(0, packet.flags());
    }

    @Test
    void marksUdpAsTruncatedWhenItsLengthFieldClaimsMoreThanWasCaptured() {
        byte[] data = pattern(50, 5);
        L4Packet packet = L4Packet.decode(fromFrame(ipv4("10.1.1.1", "10.1.1.2", PROTO_UDP, udp(40000, 5000, data, 8 + 500))));

        assertArrayEquals(data, packet.payload());
        assertTrue(packet.truncated());
    }

    @Test
    void decodesReassembledPacketsFromTheirPayloadBytes() {
        byte[] data = pattern(3000, 7);
        byte[] datagram = udp(40000, 5000, data);
        Ipv4Packet reassembled = new Ipv4Packet(TS, 1, 2, Ipv4Packet.PROTO_UDP, 9, false, 0, datagram, false, null);

        L4Packet packet = L4Packet.decode(reassembled);

        assertNotNull(packet);
        assertEquals(9, packet.ipId());
        assertArrayEquals(data, packet.payload());
    }

    @Test
    void returnsNullForMalformedTransportHeaders() {
        Ipv4Packet shortTcp = new Ipv4Packet(TS, 1, 2, Ipv4Packet.PROTO_TCP, 1, false, 0, new byte[]{1, 2, 3}, false, null);
        Ipv4Packet shortUdp = new Ipv4Packet(TS, 1, 2, Ipv4Packet.PROTO_UDP, 1, false, 0, new byte[]{1, 2, 3}, false, null);

        assertNull(L4Packet.decode(shortTcp));
        assertNull(L4Packet.decode(shortUdp));
    }

    @Test
    void returnsNullForOtherProtocols() {
        Ipv4Packet icmp = new Ipv4Packet(TS, 1, 2, 1, 1, false, 0, new byte[8], false, null);

        assertNull(L4Packet.decode(icmp));
    }

    private static Ipv4Packet fromFrame(byte[] ipPacket) {
        byte[] frame = ethernet(ipPacket);
        return Ipv4Packet.from(TS, LinkDecoder.ipv4(new RawFrame(TS, 1, frame, frame.length)), false);
    }
}
