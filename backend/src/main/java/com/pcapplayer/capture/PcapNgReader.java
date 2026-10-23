package com.pcapplayer.capture;

import java.io.EOFException;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * pcapng reader: SHB, IDB, EPB, SPB (and the obsolete PB), multiple sections and interfaces,
 * per-interface link type, {@code if_tsresol} and {@code if_tsoffset}, both byte orders.
 * Other block types are skipped.
 */
final class PcapNgReader implements CaptureReader {

    static final long SHB = 0x0A0D0D0AL;
    private static final long IDB = 1, PB = 2, SPB = 3, EPB = 6;
    private static final long BOM = 0x1A2B3C4DL;
    private static final long MAX_BLOCK = 64L * 1024 * 1024;

    private record Iface(int linkType, long snaplen, boolean binaryRes, int resExp, long offsetSec) {

        long toNanos(long ts) {
            long nanos;
            if (binaryRes) {
                if (resExp == 0) {
                    nanos = ts * 1_000_000_000L;
                } else if (resExp <= 30) {
                    nanos = (ts >>> resExp) * 1_000_000_000L + (((ts & ((1L << resExp) - 1)) * 1_000_000_000L) >>> resExp);
                } else {
                    nanos = BigInteger.valueOf(ts).multiply(BigInteger.valueOf(1_000_000_000L)).shiftRight(resExp).longValue();
                }
            } else if (resExp <= 9) {
                long mul = 1;
                for (int i = resExp; i < 9; i++) mul *= 10;
                nanos = ts * mul;
            } else {
                long div = 1;
                for (int i = 9; i < resExp; i++) div *= 10;
                nanos = ts / div;
            }
            return nanos + offsetSec * 1_000_000_000L;
        }
    }

    private final StreamInput in;
    private final List<Iface> ifaces = new ArrayList<>();
    private boolean endedAbruptly;

    /** @param firstFour the first four bytes (the SHB block type), already consumed */
    PcapNgReader(StreamInput in, byte[] firstFour) throws IOException {
        this.in = in;
        readSectionHeader();
    }

    private void readSectionHeader() throws IOException {
        byte[] lenAndBom = in.readBytes(8);
        in.littleEndian(false);
        long bom = in.u32(lenAndBom, 4);
        if (bom != BOM) {
            in.littleEndian(true);
            if (in.u32(lenAndBom, 4) != BOM) {
                throw new InvalidCaptureException("Bad pcapng byte-order magic");
            }
        }
        long total = in.u32(lenAndBom, 0);
        if (total < 28 || total > MAX_BLOCK) {
            throw new InvalidCaptureException("Bad pcapng section header length");
        }
        in.skip(total - 12);
        ifaces.clear();
    }

    @Override
    public RawFrame next() throws IOException {
        try {
            while (true) {
                byte[] head = in.readBytesOrNull(4);
                if (head == null) {
                    return null;
                }
                long type = in.u32(head, 0);
                if (type == SHB) {
                    readSectionHeader();
                    continue;
                }
                long total = in.u32(in.readBytes(4), 0);
                if (total < 12 || total > MAX_BLOCK || total % 4 != 0) {
                    throw new InvalidCaptureException("Corrupt pcapng block length " + total);
                }
                int bodyLen = (int) (total - 12);
                if (type == IDB || type == EPB || type == SPB || type == PB) {
                    byte[] body = in.readBytes(bodyLen);
                    in.skip(4);
                    RawFrame f = handle(type, body);
                    if (f != null) {
                        return f;
                    }
                } else {
                    in.skip(bodyLen + 4L);
                }
            }
        } catch (EOFException e) {
            endedAbruptly = true;
            return null;
        }
    }

    private RawFrame handle(long type, byte[] b) throws IOException {
        if (type == IDB) {
            readInterface(b);
            return null;
        }
        if (type == EPB) {
            int ifId = (int) in.u32(b, 0);
            long ts = (in.u32(b, 4) << 32) | in.u32(b, 8);
            int cap = (int) in.u32(b, 12);
            long orig = in.u32(b, 16);
            return frame(ifId, ts, b, 20, cap, orig);
        }
        if (type == PB) {
            int ifId = in.u16(b, 0);
            long ts = (in.u32(b, 4) << 32) | in.u32(b, 8);
            int cap = (int) in.u32(b, 12);
            long orig = in.u32(b, 16);
            return frame(ifId, ts, b, 20, cap, orig);
        }
        // SPB: no timestamp; belongs to interface 0
        long orig = in.u32(b, 0);
        Iface i0 = iface(0);
        int cap = (int) Math.min(b.length - 4, orig);
        if (i0.snaplen() > 0) {
            cap = (int) Math.min(cap, i0.snaplen());
        }
        byte[] data = new byte[cap];
        System.arraycopy(b, 4, data, 0, cap);
        return new RawFrame(0, i0.linkType(), data, (int) Math.max(orig, cap));
    }

    private RawFrame frame(int ifId, long ts, byte[] b, int off, int cap, long orig) throws IOException {
        Iface iface = iface(ifId);
        if (cap < 0 || off + cap > b.length) {
            throw new InvalidCaptureException("Corrupt pcapng packet block");
        }
        byte[] data = new byte[cap];
        System.arraycopy(b, off, data, 0, cap);
        return new RawFrame(iface.toNanos(ts), iface.linkType(), data, (int) Math.min(Integer.MAX_VALUE, Math.max(orig, cap)));
    }

    private Iface iface(int id) throws InvalidCaptureException {
        if (id < 0 || id >= ifaces.size()) {
            throw new InvalidCaptureException("Packet references unknown interface " + id);
        }
        return ifaces.get(id);
    }

    private void readInterface(byte[] b) {
        int linkType = in.u16(b, 0);
        long snaplen = in.u32(b, 4);
        boolean binary = false;
        int exp = 6;
        long offset = 0;
        int p = 8;
        while (p + 4 <= b.length) {
            int code = in.u16(b, p);
            int len = in.u16(b, p + 2);
            int v = p + 4;
            if (code == 0 || v + len > b.length) {
                break;
            }
            if (code == 9 && len >= 1) {
                int r = b[v] & 0xFF;
                binary = (r & 0x80) != 0;
                exp = r & 0x7F;
            } else if (code == 14 && len >= 8) {
                offset = in.u64(b, v);
            }
            p = v + ((len + 3) & ~3);
        }
        ifaces.add(new Iface(linkType, snaplen, binary, exp, offset));
    }

    @Override
    public String format() {
        return "pcapng";
    }

    @Override
    public boolean endedAbruptly() {
        return endedAbruptly;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
