package com.pcapplayer.pcap.decode;

import com.pcapplayer.pcap.capture.RawFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.pcap4j.packet.IpV4Packet;

import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.stream.Stream;

import static com.pcapplayer.testsupport.Frames.ETHERTYPE_ARP;
import static com.pcapplayer.testsupport.Frames.ETHERTYPE_IPV6;
import static com.pcapplayer.testsupport.Frames.ETHERTYPE_QINQ;
import static com.pcapplayer.testsupport.Frames.ETHERTYPE_VLAN;
import static com.pcapplayer.testsupport.Frames.ethernet;
import static com.pcapplayer.testsupport.Frames.ipv4;
import static com.pcapplayer.testsupport.Frames.linuxSll2;
import static com.pcapplayer.testsupport.Frames.linuxSll;
import static com.pcapplayer.testsupport.Frames.loopback;
import static com.pcapplayer.testsupport.Frames.pattern;
import static com.pcapplayer.testsupport.Frames.udp;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Locating the IPv4 packet in every supported link layer (pure Java, no libpcap needed). */
class LinkDecoderTest {

    private static final byte[] IP_PACKET = ipv4("192.168.1.10", "192.168.1.20", 17, udp(4000, 5000, pattern(25, 3)));

    static Stream<Arguments> supportedLinkLayers() {
        return Stream.of(
                Arguments.of("Ethernet", LinkDecoder.DLT_EN10MB, ethernet(IP_PACKET)),
                Arguments.of("Ethernet + 802.1Q", LinkDecoder.DLT_EN10MB, ethernet(IP_PACKET, ETHERTYPE_VLAN)),
                Arguments.of("Ethernet + QinQ", LinkDecoder.DLT_EN10MB, ethernet(IP_PACKET, ETHERTYPE_QINQ, ETHERTYPE_VLAN)),
                Arguments.of("Linux SLL", LinkDecoder.DLT_LINUX_SLL, linuxSll(IP_PACKET)),
                Arguments.of("Linux SLL2", LinkDecoder.DLT_LINUX_SLL2, linuxSll2(IP_PACKET)),
                Arguments.of("BSD loopback, little-endian", LinkDecoder.DLT_NULL, loopback(IP_PACKET, ByteOrder.LITTLE_ENDIAN)),
                Arguments.of("BSD loopback, big-endian", LinkDecoder.DLT_NULL, loopback(IP_PACKET, ByteOrder.BIG_ENDIAN)),
                Arguments.of("DLT_LOOP", LinkDecoder.DLT_LOOP, loopback(IP_PACKET, ByteOrder.BIG_ENDIAN)),
                Arguments.of("DLT_RAW (12)", 12, IP_PACKET),
                Arguments.of("LINKTYPE_RAW (101)", 101, IP_PACKET),
                Arguments.of("DLT_IPV4", LinkDecoder.DLT_IPV4, IP_PACKET));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("supportedLinkLayers")
    void findsTheIpv4Packet(String name, int linkType, byte[] frameData) {
        IpV4Packet ip = LinkDecoder.ipv4(new RawFrame(0, linkType, frameData, frameData.length));

        assertNotNull(ip, name);
        assertEquals("192.168.1.10", ip.getHeader().getSrcAddr().getHostAddress());
        assertEquals("192.168.1.20", ip.getHeader().getDstAddr().getHostAddress());
        assertEquals(IP_PACKET.length, ip.length());
    }

    @Test
    void ignoresNonIpv4EtherTypes() {
        byte[] arp = ethernet(ETHERTYPE_ARP, new byte[28]);
        byte[] ipv6 = ethernet(ETHERTYPE_IPV6, new byte[40]);

        assertNull(LinkDecoder.ipv4(new RawFrame(0, LinkDecoder.DLT_EN10MB, arp, arp.length)));
        assertNull(LinkDecoder.ipv4(new RawFrame(0, LinkDecoder.DLT_EN10MB, ipv6, ipv6.length)));
    }

    @Test
    void ignoresUnknownLinkTypes() {
        assertNull(LinkDecoder.ipv4(new RawFrame(0, 9999, IP_PACKET, IP_PACKET.length)));
    }

    @Test
    void ignoresLoopbackFramesOfAnotherAddressFamily() {
        byte[] frame = loopback(IP_PACKET, ByteOrder.LITTLE_ENDIAN);
        frame[0] = 24; // AF_INET6 on some BSDs

        assertNull(LinkDecoder.ipv4(new RawFrame(0, LinkDecoder.DLT_NULL, frame, frame.length)));
    }

    @Test
    void ignoresFramesCutBeforeTheIpHeader() {
        byte[] frame = Arrays.copyOf(ethernet(IP_PACKET), 20);

        assertNull(LinkDecoder.ipv4(new RawFrame(0, LinkDecoder.DLT_EN10MB, frame, 100)));
    }
}
