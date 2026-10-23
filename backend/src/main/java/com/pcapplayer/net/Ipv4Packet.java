package com.pcapplayer.net;

import java.util.Arrays;

/**
 * An IPv4 packet (possibly one fragment). {@code payload} is the IP payload as captured.
 *
 * @param truncated the capture cut off part of this packet (snaplen)
 */
public record Ipv4Packet(long tsNanos, int src, int dst, int protocol, int id,
                         boolean moreFragments, int fragmentOffset, byte[] payload, boolean truncated) {

    public static final int PROTO_TCP = 6;
    public static final int PROTO_UDP = 17;

    public boolean isFragment() {
        return moreFragments || fragmentOffset > 0;
    }

    /**
     * Parses the IPv4 header at {@code off}. The payload length is derived robustly: the IP
     * total-length is trusted only when it is consistent with the captured bytes (it then trims
     * Ethernet padding); otherwise (GRO/LRO super-packets, total-length 0) the captured length is used.
     *
     * @return the packet, or null if the header is not a valid IPv4 header
     */
    public static Ipv4Packet parse(long ts, byte[] d, int off, boolean frameTruncated) {
        int avail = d.length - off;
        if (avail < 20 || (d[off] & 0xF0) != 0x40) return null;
        int ihl = (d[off] & 0x0F) * 4;
        if (ihl < 20 || avail < ihl) return null;
        int totalLen = LinkDecoder.u16(d, off + 2);
        int id = LinkDecoder.u16(d, off + 4);
        int flagsFrag = LinkDecoder.u16(d, off + 6);
        boolean mf = (flagsFrag & 0x2000) != 0;
        int fragOff = (flagsFrag & 0x1FFF) * 8;
        int proto = d[off + 9] & 0xFF;
        int src = readInt(d, off + 12);
        int dst = readInt(d, off + 16);

        int ipLen;
        boolean truncated;
        if (totalLen >= ihl && totalLen <= avail) {
            ipLen = totalLen;
            truncated = false; // the whole IP packet was captured (snaplen only cut link trailer, if anything)
        } else {
            ipLen = avail;
            truncated = frameTruncated;
        }
        byte[] payload = Arrays.copyOfRange(d, off + ihl, off + ipLen);
        return new Ipv4Packet(ts, src, dst, proto, id, mf, fragOff, payload, truncated);
    }

    static int readInt(byte[] d, int off) {
        return (d[off] & 0xFF) << 24 | (d[off + 1] & 0xFF) << 16 | (d[off + 2] & 0xFF) << 8 | (d[off + 3] & 0xFF);
    }

    public static String ipToString(int ip) {
        return ((ip >>> 24) & 0xFF) + "." + ((ip >>> 16) & 0xFF) + "." + ((ip >>> 8) & 0xFF) + "." + (ip & 0xFF);
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
