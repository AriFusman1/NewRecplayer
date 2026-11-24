package com.pcapplayer.pcapProcessing.decode;

import com.pcapplayer.pcapProcessing.capture.RawFrame;
import org.pcap4j.packet.IllegalRawDataException;
import org.pcap4j.packet.IpV4Packet;
import org.pcap4j.packet.Packet;
import org.pcap4j.packet.factory.PacketFactories;
import org.pcap4j.packet.namednumber.DataLinkType;
import org.pcap4j.packet.namednumber.EtherType;
import org.pcap4j.util.ByteArrays;

import java.nio.ByteOrder;

/**
 * Finds the IPv4 packet inside a link-layer frame. pcap4j decodes the link layer (Ethernet,
 * 802.1Q VLAN, Linux SLL, BSD loopback, raw IP); for encapsulations pcap4j 1.8 does not know
 * (Linux SLL2, DLT_LOOP, DLT_IPV4, QinQ, big-endian loopback) the IPv4 header is located here and
 * handed to pcap4j's IPv4 decoder.
 */
public final class LinkDecoder {

    public static final int DLT_NULL = 0;
    public static final int DLT_EN10MB = 1;
    public static final int DLT_LOOP = 108;
    public static final int DLT_LINUX_SLL = 113;
    public static final int DLT_IPV4 = 228;
    public static final int DLT_LINUX_SLL2 = 276;

    /** libpcap maps LINKTYPE_RAW (101) to DLT_RAW, which is 12 or 14 depending on the platform. */
    private static final int LINKTYPE_RAW = 101, DLT_RAW_A = 12, DLT_RAW_B = 14;

    /** Where each Ethernet-like header keeps its EtherType / protocol field, and where the payload starts. */
    private static final int ETHERNET_TYPE_OFFSET = 12, ETHERNET_HEADER_LENGTH = 14;
    private static final int SLL_TYPE_OFFSET = 14, SLL_HEADER_LENGTH = 16;
    private static final int SLL2_TYPE_OFFSET = 0, SLL2_HEADER_LENGTH = 20;
    private static final int LOOPBACK_HEADER_LENGTH = 4;
    private static final int AF_INET = 2;

    /** A VLAN tag is TCI (2 bytes) followed by the inner EtherType (2 bytes). */
    private static final int VLAN_TAG_LENGTH = 4;
    private static final int ETHERTYPE_QINQ = 0x88A8, ETHERTYPE_QINQ_OLD = 0x9100;

    private LinkDecoder() {
    }

    /** @return the IPv4 packet carried by the frame, or null if it carries none (or it is malformed) */
    public static IpV4Packet ipv4(RawFrame frame) {
        byte[] frameData = frame.data();

        try {
            Packet decoded = PacketFactories.getFactory(Packet.class, DataLinkType.class)
                    .newInstance(frameData, 0, frameData.length, DataLinkType.getInstance(frame.linkType()));
            IpV4Packet ipPacket = decoded.get(IpV4Packet.class);
            if (ipPacket != null) return ipPacket;
        } catch (RuntimeException e) {
            // fall through to the manual offset lookup
        }
        int ipOffset = ipv4Offset(frame.linkType(), frameData);
        if (ipOffset < 0) return null;

        try {
            return IpV4Packet.newPacket(frameData, ipOffset, frameData.length - ipOffset);
        } catch (IllegalRawDataException e) {
            return null;
        }
    }

    /** @return offset of the IPv4 header, or -1 if the frame does not carry IPv4 */
    static int ipv4Offset(int linkType, byte[] frameData) {
        switch (linkType) {
            case DLT_EN10MB:
                return afterEtherType(frameData, ETHERNET_TYPE_OFFSET, ETHERNET_HEADER_LENGTH);
            case DLT_LINUX_SLL:
                return afterEtherType(frameData, SLL_TYPE_OFFSET, SLL_HEADER_LENGTH);
            case DLT_LINUX_SLL2:
                return afterEtherType(frameData, SLL2_TYPE_OFFSET, SLL2_HEADER_LENGTH);
            case LINKTYPE_RAW:
            case DLT_IPV4:
            case DLT_RAW_A:
            case DLT_RAW_B:
                return startsWithIpv4(frameData, 0) ? 0 : -1;
            case DLT_NULL:
            case DLT_LOOP:
                return isLoopbackInet(frameData) && startsWithIpv4(frameData, LOOPBACK_HEADER_LENGTH)
                        ? LOOPBACK_HEADER_LENGTH : -1;
            default:
                return -1;
        }
    }

    /** The 4-byte loopback address family is written in host byte order, so either order may appear. */
    private static boolean isLoopbackInet(byte[] frameData) {
        if (frameData.length < LOOPBACK_HEADER_LENGTH) return false;
        return ByteArrays.getInt(frameData, 0, ByteOrder.BIG_ENDIAN) == AF_INET
                || ByteArrays.getInt(frameData, 0, ByteOrder.LITTLE_ENDIAN) == AF_INET;
    }

    private static boolean startsWithIpv4(byte[] frameData, int offset) {
        return frameData.length > offset && (frameData[offset] & 0xF0) == 0x40;
    }

    /** Ethernet-style: EtherType at {@code typeOffset}, payload at {@code payloadOffset}, VLAN tags skipped. */
    private static int afterEtherType(byte[] frameData, int typeOffset, int payloadOffset) {
        if (frameData.length < payloadOffset) return -1;
        int etherType = unsignedShort(frameData, typeOffset);
        int offset = payloadOffset;

        while (isVlanTag(etherType)) {
            if (frameData.length < offset + VLAN_TAG_LENGTH) return -1;
            etherType = unsignedShort(frameData, offset + 2);
            offset += VLAN_TAG_LENGTH;
        }
        return etherType == unsignedValue(EtherType.IPV4) ? offset : -1;
    }

    private static boolean isVlanTag(int etherType) {
        return etherType == unsignedValue(EtherType.DOT1Q_VLAN_TAGGED_FRAMES)
                || etherType == ETHERTYPE_QINQ || etherType == ETHERTYPE_QINQ_OLD;
    }

    private static int unsignedValue(EtherType etherType) {
        return Short.toUnsignedInt(etherType.value());
    }

    private static int unsignedShort(byte[] data, int offset) {
        return Short.toUnsignedInt(ByteArrays.getShort(data, offset));
    }
}
