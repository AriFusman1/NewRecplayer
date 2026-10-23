package com.pcapplayer.capture;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/** Minimal buffered binary reader with switchable byte order. */
final class StreamInput {

    private final InputStream in;
    private boolean littleEndian;

    StreamInput(InputStream in) {
        this.in = in;
    }

    void littleEndian(boolean le) {
        this.littleEndian = le;
    }

    boolean littleEndian() {
        return littleEndian;
    }

    /** Reads exactly {@code n} bytes; throws EOFException if the stream ends first. */
    byte[] readBytes(int n) throws IOException {
        byte[] b = in.readNBytes(n);
        if (b.length != n) {
            throw new EOFException();
        }
        return b;
    }

    /** Reads exactly n bytes, or returns null on a clean EOF before the first byte. */
    byte[] readBytesOrNull(int n) throws IOException {
        byte[] b = in.readNBytes(n);
        if (b.length == 0) {
            return null;
        }
        if (b.length != n) {
            throw new EOFException();
        }
        return b;
    }

    void skip(long n) throws IOException {
        long left = n;
        while (left > 0) {
            long s = in.skip(left);
            if (s <= 0) {
                if (in.read() < 0) {
                    throw new EOFException();
                }
                s = 1;
            }
            left -= s;
        }
    }

    int u16(byte[] b, int off) {
        return littleEndian
                ? (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8
                : (b[off] & 0xFF) << 8 | (b[off + 1] & 0xFF);
    }

    long u32(byte[] b, int off) {
        long v = littleEndian
                ? (b[off] & 0xFFL) | (b[off + 1] & 0xFFL) << 8 | (b[off + 2] & 0xFFL) << 16 | (b[off + 3] & 0xFFL) << 24
                : (b[off] & 0xFFL) << 24 | (b[off + 1] & 0xFFL) << 16 | (b[off + 2] & 0xFFL) << 8 | (b[off + 3] & 0xFFL);
        return v;
    }

    long u64(byte[] b, int off) {
        long a = u32(b, off);
        long c = u32(b, off + 4);
        return littleEndian ? (c << 32) | a : (a << 32) | c;
    }

    void close() throws IOException {
        in.close();
    }
}
