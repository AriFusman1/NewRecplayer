package com.pcapplayer.net;

/**
 * A (reassembled) IPv4 TCP or UDP packet.
 *
 * @param payload   application bytes as captured
 * @param truncated part of the packet was cut off by the capture's snaplen
 * @param seq       TCP sequence number (unsigned 32 bit), 0 for UDP
 * @param ack       TCP acknowledgement number (unsigned 32 bit), 0 for UDP
 * @param flags     TCP flags, 0 for UDP
 */
public record L4Packet(long tsNanos, int protocol, int srcIp, int dstIp, int srcPort, int dstPort, int ipId,
                       byte[] payload, boolean truncated, long seq, long ack, int flags) {

    public static final int FIN = 0x01, SYN = 0x02, RST = 0x04, ACK = 0x10;

    public boolean tcp() {
        return protocol == Ipv4Packet.PROTO_TCP;
    }

    public boolean has(int flag) {
        return (flags & flag) != 0;
    }

    /** Decodes the TCP/UDP header of a whole (non-fragment) IPv4 packet; null if not TCP/UDP or too short. */
    public static L4Packet decode(Ipv4Packet ip) {
        byte[] p = ip.payload();
        if (ip.protocol() == Ipv4Packet.PROTO_UDP) {
            if (p.length < 8) return null;
            int udpLen = LinkDecoder.u16(p, 4);
            int dataLen = p.length - 8;
            boolean truncated = ip.truncated();
            if (udpLen >= 8 && udpLen - 8 <= dataLen) {
                dataLen = udpLen - 8;
            } else if (udpLen > 8 && udpLen - 8 > dataLen) {
                truncated = true;
            }
            byte[] data = new byte[dataLen];
            System.arraycopy(p, 8, data, 0, dataLen);
            return new L4Packet(ip.tsNanos(), ip.protocol(), ip.src(), ip.dst(),
                    LinkDecoder.u16(p, 0), LinkDecoder.u16(p, 2), ip.id(), data, truncated, 0, 0, 0);
        }
        if (ip.protocol() == Ipv4Packet.PROTO_TCP) {
            if (p.length < 20) return null;
            int hdr = ((p[12] & 0xF0) >> 4) * 4;
            if (hdr < 20 || hdr > p.length) return null;
            long seq = Ipv4Packet.readInt(p, 4) & 0xFFFFFFFFL;
            long ack = Ipv4Packet.readInt(p, 8) & 0xFFFFFFFFL;
            int flags = p[13] & 0x3F;
            byte[] data = new byte[p.length - hdr];
            System.arraycopy(p, hdr, data, 0, data.length);
            return new L4Packet(ip.tsNanos(), ip.protocol(), ip.src(), ip.dst(),
                    LinkDecoder.u16(p, 0), LinkDecoder.u16(p, 2), ip.id(), data, ip.truncated(), seq, ack, flags);
        }
        return null;
    }
}
