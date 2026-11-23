package com.pcapplayer.pcap.decode;

import org.pcap4j.packet.IllegalRawDataException;
import org.pcap4j.packet.Packet;
import org.pcap4j.packet.TcpPacket;
import org.pcap4j.packet.UdpPacket;

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

    public static final int FIN = 0x01, SYN = 0x02, RST = 0x04, PSH = 0x08, ACK = 0x10, URG = 0x20;
    private static final byte[] EMPTY = new byte[0];

    public boolean tcp() {
        return protocol == Ipv4Packet.PROTO_TCP;
    }

    public boolean has(int flag) {
        return (flags & flag) != 0;
    }

    /**
     * Decodes the TCP/UDP header of a whole (non-fragment) IPv4 packet with pcap4j, reusing the
     * transport packet pcap4j already decoded when there is one.
     *
     * @return null if not TCP/UDP or the header is malformed
     */
    public static L4Packet decode(Ipv4Packet ipPacket) {
        try {
            if (ipPacket.protocol() == Ipv4Packet.PROTO_UDP) {
                return fromUdp(ipPacket, udpOf(ipPacket));
            }
            if (ipPacket.protocol() == Ipv4Packet.PROTO_TCP) {
                return fromTcp(ipPacket, tcpOf(ipPacket));
            }
        } catch (IllegalRawDataException e) {
            return null;
        }
        return null;
    }

    private static UdpPacket udpOf(Ipv4Packet ipPacket) throws IllegalRawDataException {
        if (ipPacket.transport() instanceof UdpPacket alreadyDecoded) {
            return alreadyDecoded;
        }
        byte[] ipPayload = ipPacket.payload();
        return UdpPacket.newPacket(ipPayload, 0, ipPayload.length);
    }

    private static TcpPacket tcpOf(Ipv4Packet ipPacket) throws IllegalRawDataException {
        if (ipPacket.transport() instanceof TcpPacket alreadyDecoded) {
            return alreadyDecoded;
        }
        byte[] ipPayload = ipPacket.payload();
        return TcpPacket.newPacket(ipPayload, 0, ipPayload.length);
    }

    private static L4Packet fromUdp(Ipv4Packet ipPacket, UdpPacket udpPacket) {
        UdpPacket.UdpHeader header = udpPacket.getHeader();
        byte[] data = bytesOf(udpPacket.getPayload());
        int declaredDataLength = header.getLengthAsInt() - header.length();
        boolean truncated = ipPacket.truncated() || declaredDataLength > data.length;
        return new L4Packet(ipPacket.tsNanos(), ipPacket.protocol(), ipPacket.src(), ipPacket.dst(),
                header.getSrcPort().valueAsInt(), header.getDstPort().valueAsInt(), ipPacket.id(),
                data, truncated, 0, 0, 0);
    }

    private static L4Packet fromTcp(Ipv4Packet ipPacket, TcpPacket tcpPacket) {
        TcpPacket.TcpHeader header = tcpPacket.getHeader();
        int flags = (header.getFin() ? FIN : 0)
                | (header.getSyn() ? SYN : 0)
                | (header.getRst() ? RST : 0)
                | (header.getPsh() ? PSH : 0)
                | (header.getAck() ? ACK : 0)
                | (header.getUrg() ? URG : 0);
        return new L4Packet(ipPacket.tsNanos(), ipPacket.protocol(), ipPacket.src(), ipPacket.dst(),
                header.getSrcPort().valueAsInt(), header.getDstPort().valueAsInt(), ipPacket.id(),
                bytesOf(tcpPacket.getPayload()), ipPacket.truncated(),
                header.getSequenceNumberAsLong(), header.getAcknowledgmentNumberAsLong(), flags);
    }

    private static byte[] bytesOf(Packet payload) {
        return payload == null ? EMPTY : payload.getRawData();
    }
}
