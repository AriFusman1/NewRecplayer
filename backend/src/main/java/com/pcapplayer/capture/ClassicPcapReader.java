package com.pcapplayer.capture;

import java.io.EOFException;
import java.io.IOException;

/** Classic libpcap format: both byte orders, microsecond and nanosecond magic. */
final class ClassicPcapReader implements CaptureReader {

    static final long MAGIC_US = 0xA1B2C3D4L;
    static final long MAGIC_NS = 0xA1B23C4DL;
    /** Sanity cap for a single record; GRO packets are ~64 KB, anything far beyond is corruption. */
    private static final long MAX_RECORD = 16L * 1024 * 1024;

    private final StreamInput in;
    private final boolean nanos;
    private final int linkType;
    private boolean endedAbruptly;

    ClassicPcapReader(StreamInput in, byte[] magic) throws IOException {
        this.in = in;
        long be = (magic[0] & 0xFFL) << 24 | (magic[1] & 0xFFL) << 16 | (magic[2] & 0xFFL) << 8 | (magic[3] & 0xFFL);
        if (be == MAGIC_US || be == MAGIC_NS) {
            in.littleEndian(false);
            nanos = be == MAGIC_NS;
        } else {
            in.littleEndian(true);
            long le = in.u32(magic, 0);
            if (le != MAGIC_US && le != MAGIC_NS) {
                throw new InvalidCaptureException("Not a pcap file");
            }
            nanos = le == MAGIC_NS;
        }
        byte[] rest = in.readBytes(20);
        // rest: version(2+2) thiszone(4) sigfigs(4) snaplen(4) network(4)
        linkType = (int) (in.u32(rest, 16) & 0xFFFF);
    }

    @Override
    public RawFrame next() throws IOException {
        try {
            byte[] h = in.readBytesOrNull(16);
            if (h == null) {
                return null;
            }
            long sec = in.u32(h, 0);
            long frac = in.u32(h, 4);
            long inclLen = in.u32(h, 8);
            long origLen = in.u32(h, 12);
            if (inclLen > MAX_RECORD) {
                throw new InvalidCaptureException("Corrupt pcap record length " + inclLen);
            }
            byte[] data = in.readBytes((int) inclLen);
            long ts = sec * 1_000_000_000L + (nanos ? frac : frac * 1000L);
            return new RawFrame(ts, linkType, data, (int) Math.min(Integer.MAX_VALUE, Math.max(origLen, inclLen)));
        } catch (EOFException e) {
            endedAbruptly = true;
            return null;
        }
    }

    @Override
    public String format() {
        return "pcap";
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
