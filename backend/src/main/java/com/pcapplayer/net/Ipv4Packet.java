package com.pcapplayer.net;

import org.pcap4j.packet.IpV4Packet;
import org.pcap4j.packet.Packet;
import org.pcap4j.util.ByteArrays;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * An IPv4 packet (possibly one fragment). {@code payload} is the IP payload as captured.
 *
 * @param truncated the capture cut off part of this packet (snaplen)
 * @param transport the TCP/UDP packet pcap4j already decoded from this (unfragmented) packet,
 *                  or null for fragments and reassembled packets
 */
public record Ipv4Packet(long tsNanos, int src, int dst, int protocol, int id,
                         boolean moreFragments, int fragmentOffset, byte[] payload, boolean truncated,
                         Packet transport) {

    public static final int PROTO_TCP = 6;
    public static final int PROTO_UDP = 17;

    /** The IPv4 fragment-offset field counts 8-byte units. */
    private static final int FRAGMENT_OFFSET_UNIT = 8;
    private static final int FRAGMENT_OFFSET_MASK = 0x1FFF;

    public boolean isFragment() {
        return moreFragments || fragmentOffset > 0;
    }

    /**
     * Converts a pcap4j-decoded IPv4 packet. pcap4j already trusts the IP total-length only when it
     * fits the captured bytes (trimming Ethernet padding) and uses the captured length otherwise
     * (GRO/LRO super-packets with total-length 0, snaplen truncation).
     */
    public static Ipv4Packet from(long tsNanos, IpV4Packet ipPacket, boolean frameTruncated) {
        IpV4Packet.IpV4Header header = ipPacket.getHeader();
        Packet ipPayload = ipPacket.getPayload();
        byte[] payloadBytes = ipPayload == null ? new byte[0] : ipPayload.getRawData();

        int headerLength = header.length();
        int totalLength = header.getTotalLengthAsInt();
        // The whole IP packet was captured iff its total-length fits in what pcap4j kept.
        boolean fullyCaptured = totalLength >= headerLength && totalLength <= headerLength + payloadBytes.length;

        boolean moreFragments = header.getMoreFragmentFlag();
        int fragmentOffset = (header.getFragmentOffset() & FRAGMENT_OFFSET_MASK) * FRAGMENT_OFFSET_UNIT;
        boolean fragment = moreFragments || fragmentOffset > 0;

        return new Ipv4Packet(tsNanos,
                ByteArrays.getInt(header.getSrcAddr().getAddress(), 0),
                ByteArrays.getInt(header.getDstAddr().getAddress(), 0),
                Byte.toUnsignedInt(header.getProtocol().value()),
                header.getIdentificationAsInt(),
                moreFragments, fragmentOffset, payloadBytes,
                !fullyCaptured && frameTruncated,
                fragment ? null : ipPayload);
    }

    public static String ipToString(int ip) {
        try {
            return InetAddress.getByAddress(ByteArrays.toByteArray(ip)).getHostAddress();
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e); // cannot happen for a 4-byte address
        }
    }

    /** @return the address as int, or throws IllegalArgumentException for a non dotted-quad string */
    public static int parseIp(String s) {
        String[] p = s.trim().split("\\.");
        if (p.length != 4) throw new IllegalArgumentException("Invalid IPv4 address: " + s);
        int v = 0;
        for (String part : p) {
            int b;
            try {
                b = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid IPv4 address: " + s);
            }
            if (b < 0 || b > 255) throw new IllegalArgumentException("Invalid IPv4 address: " + s);
            v = (v << 8) | b;
        }
        return v;
    }
}
