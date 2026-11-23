package com.pcapplayer.testsupport;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Builds raw packet bytes (IPv4, TCP, UDP and link-layer headers) for tests. */
public class Frames {

    public static final int PROTO_TCP = 6, PROTO_UDP = 17;
    public static final int FIN = 0x01, SYN = 0x02, RST = 0x04, PSH = 0x08, ACK = 0x10;
    public static final int ETHERTYPE_IPV4 = 0x0800, ETHERTYPE_ARP = 0x0806, ETHERTYPE_IPV6 = 0x86DD;
    public static final int ETHERTYPE_VLAN = 0x8100, ETHERTYPE_QINQ = 0x88A8;

    private static final int IPV4_HEADER_LENGTH = 20;

    private Frames() {
    }

    public static byte[] address(String dottedQuad) {
        String[] parts = dottedQuad.split("\\.");
        byte[] address = new byte[4];
        for (int i = 0; i < 4; i++) address[i] = (byte) Integer.parseInt(parts[i]);
        return address;
    }

    /** An unfragmented IPv4 packet whose total-length matches its payload. */
    public static byte[] ipv4(String src, String dst, int protocol, byte[] payload) {
        return ipv4(src, dst, protocol, payload, 1, false, 0, IPV4_HEADER_LENGTH + payload.length);
    }

    /**
     * @param fragmentOffsetBytes offset of this fragment's payload in bytes (multiple of 8)
     * @param totalLength         value written to the total-length field (0 imitates GRO)
     */
    public static byte[] ipv4(String src, String dst, int protocol, byte[] payload, int identification,
                              boolean moreFragments, int fragmentOffsetBytes, int totalLength) {
        ByteBuffer buffer = ByteBuffer.allocate(IPV4_HEADER_LENGTH + payload.length);
        buffer.put((byte) 0x45).put((byte) 0)
                .putShort((short) totalLength)
                .putShort((short) identification)
                .putShort((short) ((moreFragments ? 0x2000 : 0) | (fragmentOffsetBytes / 8)))
                .put((byte) 64).put((byte) protocol)
                .putShort((short) 0) // checksum is never validated
                .put(address(src)).put(address(dst))
                .put(payload);
        return buffer.array();
    }

    public static byte[] tcp(int srcPort, int dstPort, long seq, long ack, int flags, byte[] data) {
        return ByteBuffer.allocate(20 + data.length)
                .putShort((short) srcPort).putShort((short) dstPort)
                .putInt((int) seq).putInt((int) ack)
                .put((byte) (5 << 4)).put((byte) flags)
                .putShort((short) 65535).putShort((short) 0).putShort((short) 0)
                .put(data).array();
    }

    public static byte[] udp(int srcPort, int dstPort, byte[] data) {
        return udp(srcPort, dstPort, data, 8 + data.length);
    }

    /** @param lengthField value written to the UDP length field */
    public static byte[] udp(int srcPort, int dstPort, byte[] data, int lengthField) {
        return ByteBuffer.allocate(8 + data.length)
                .putShort((short) srcPort).putShort((short) dstPort)
                .putShort((short) lengthField).putShort((short) 0)
                .put(data).array();
    }

    /** Ethernet II carrying IPv4, with optional VLAN tags given by their tag EtherTypes (outermost first). */
    public static byte[] ethernet(byte[] ipPacket, int... vlanTagTypes) {
        return ethernet(ETHERTYPE_IPV4, ipPacket, vlanTagTypes);
    }

    public static byte[] ethernet(int etherType, byte[] body, int... vlanTagTypes) {
        ByteBuffer buffer = ByteBuffer.allocate(14 + 4 * vlanTagTypes.length + body.length);
        buffer.put(new byte[12]); // destination + source MAC
        // Each VLAN tag is [tag EtherType][TCI]; the payload's EtherType follows the last tag.
        for (int i = 0; i < vlanTagTypes.length; i++) {
            buffer.putShort((short) vlanTagTypes[i]).putShort((short) (100 + i));
        }
        buffer.putShort((short) etherType).put(body);
        return buffer.array();
    }

    /** Linux cooked capture v1 (16-byte header). */
    public static byte[] linuxSll(byte[] ipPacket) {
        return ByteBuffer.allocate(16 + ipPacket.length)
                .putShort((short) 0).putShort((short) 1).putShort((short) 6).put(new byte[8])
                .putShort((short) ETHERTYPE_IPV4).put(ipPacket).array();
    }

    /** Linux cooked capture v2 (20-byte header, protocol first). */
    public static byte[] linuxSll2(byte[] ipPacket) {
        return ByteBuffer.allocate(20 + ipPacket.length)
                .putShort((short) ETHERTYPE_IPV4).putShort((short) 0).putInt(2)
                .putShort((short) 1).put((byte) 0).put((byte) 6).put(new byte[8])
                .put(ipPacket).array();
    }

    /** BSD loopback: 4-byte address family (AF_INET = 2) in the given byte order. */
    public static byte[] loopback(byte[] ipPacket, ByteOrder order) {
        return ByteBuffer.allocate(4 + ipPacket.length).order(order).putInt(2)
                .order(ByteOrder.BIG_ENDIAN).put(ipPacket).array();
    }

    /** Deterministic test payload. */
    public static byte[] pattern(int length, int seed) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) data[i] = (byte) (i * 7 + seed);
        return data;
    }
}
