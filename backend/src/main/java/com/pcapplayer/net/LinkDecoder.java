package com.pcapplayer.net;

/** Locates the IPv4 header inside a link-layer frame. */
public final class LinkDecoder {

    public static final int LINKTYPE_NULL = 0;
    public static final int LINKTYPE_ETHERNET = 1;
    public static final int LINKTYPE_RAW = 101;
    public static final int LINKTYPE_LOOP = 108;
    public static final int LINKTYPE_LINUX_SLL = 113;
    public static final int LINKTYPE_IPV4 = 228;
    public static final int LINKTYPE_LINUX_SLL2 = 276;
    /** Some platforms write DLT_RAW (12 / 14) instead of LINKTYPE_RAW. */
    private static final int DLT_RAW_A = 12, DLT_RAW_B = 14;

    private static final int ETH_IPV4 = 0x0800;
    private static final int ETH_VLAN = 0x8100, ETH_QINQ = 0x88A8, ETH_QINQ_OLD = 0x9100;

    private LinkDecoder() {
    }

    /** @return offset of the IPv4 header, or -1 if the frame does not carry IPv4 */
    public static int ipv4Offset(int linkType, byte[] d) {
        switch (linkType) {
            case LINKTYPE_ETHERNET:
                return afterEtherType(d, 12);
            case LINKTYPE_LINUX_SLL:
                return afterEtherType(d, 14);
            case LINKTYPE_LINUX_SLL2:
                return afterEtherType(d, 0, 20);
            case LINKTYPE_RAW:
            case LINKTYPE_IPV4:
            case DLT_RAW_A:
            case DLT_RAW_B:
                return d.length > 0 && (d[0] & 0xF0) == 0x40 ? 0 : -1;
            case LINKTYPE_NULL:
            case LINKTYPE_LOOP:
                if (d.length < 5) return -1;
                // 4-byte address family in host or network order; AF_INET = 2
                boolean inet = (d[0] == 2 && d[1] == 0 && d[2] == 0 && d[3] == 0) || (d[0] == 0 && d[1] == 0 && d[2] == 0 && d[3] == 2);
                return inet && (d[4] & 0xF0) == 0x40 ? 4 : -1;
            default:
                return -1;
        }
    }

    /** Ethernet-style: EtherType at {@code typeOff}, payload right after it, VLAN tags skipped. */
    private static int afterEtherType(byte[] d, int typeOff) {
        return afterEtherType(d, typeOff, typeOff + 2);
    }

    private static int afterEtherType(byte[] d, int typeOff, int payloadOff) {
        if (d.length < payloadOff) return -1;
        int type = u16(d, typeOff);
        int off = payloadOff;
        // 802.1Q / QinQ: each tag is TCI(2) + inner EtherType(2)
        while (type == ETH_VLAN || type == ETH_QINQ || type == ETH_QINQ_OLD) {
            if (d.length < off + 4) return -1;
            type = u16(d, off + 2);
            off += 4;
        }
        return type == ETH_IPV4 ? off : -1;
    }

    static int u16(byte[] d, int off) {
        return (d[off] & 0xFF) << 8 | (d[off + 1] & 0xFF);
    }
}
