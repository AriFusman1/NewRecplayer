package com.pcapplayer.testsupport;

import com.pcapplayer.pcap.capture.CaptureReader;
import com.pcapplayer.pcap.capture.CaptureReaders;
import org.junit.jupiter.api.Assumptions;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Writes classic pcap and pcapng files for tests. */
public class CaptureFiles {

    public static final int LINKTYPE_ETHERNET = 1;
    public static final int LINKTYPE_LINUX_SLL2 = 276;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    /** One captured frame; {@code originalLength > data.length} imitates snaplen truncation. */
    public record Frame(long tsNanos, byte[] data, int originalLength) {
        public Frame(long tsNanos, byte[] data) {
            this(tsNanos, data, data.length);
        }
    }

    /** One pcapng packet on interface {@code interfaceId}. */
    public record InterfaceFrame(int interfaceId, long tsNanos, byte[] data) {
    }

    private CaptureFiles() {
    }

    public static Path writePcap(Path file, int linkType, ByteOrder order, boolean nanosecondResolution,
                                 List<Frame> frames) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int magic = nanosecondResolution ? 0xA1B23C4D : 0xA1B2C3D4;
        out.write(ByteBuffer.allocate(24).order(order)
                .putInt(magic).putShort((short) 2).putShort((short) 4)
                .putInt(0).putInt(0).putInt(262144).putInt(linkType).array());
        for (Frame frame : frames) {
            long seconds = frame.tsNanos() / NANOS_PER_SECOND;
            long fraction = frame.tsNanos() % NANOS_PER_SECOND;
            if (!nanosecondResolution) fraction /= 1000;
            out.write(ByteBuffer.allocate(16).order(order)
                    .putInt((int) seconds).putInt((int) fraction)
                    .putInt(frame.data().length).putInt(frame.originalLength()).array());
            out.write(frame.data());
        }
        Files.write(file, out.toByteArray());
        return file;
    }

    public static Path writePcap(Path file, int linkType, List<Frame> frames) throws IOException {
        return writePcap(file, linkType, ByteOrder.LITTLE_ENDIAN, false, frames);
    }

    /**
     * Little-endian pcapng with one interface per entry of {@code interfaceLinkTypes}, all using
     * timestamp resolution 10^-{@code tsResolutionExponent} s.
     */
    public static Path writePcapng(Path file, List<Integer> interfaceLinkTypes, int tsResolutionExponent,
                                   List<InterfaceFrame> frames) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(block(0x0A0D0D0A, ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x1A2B3C4D).putShort((short) 1).putShort((short) 0).putLong(-1).array()));
        for (int linkType : interfaceLinkTypes) {
            out.write(block(1, ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
                    .putShort((short) linkType).putShort((short) 0).putInt(0)
                    .putShort((short) 9).putShort((short) 1).put((byte) tsResolutionExponent).put(new byte[3]) // if_tsresol
                    .putShort((short) 0).putShort((short) 0) // opt_endofopt
                    .array()));
        }
        out.write(block(0x0BAD, "an unknown block type that must be skipped".getBytes()));
        long unitsPerSecond = (long) Math.pow(10, tsResolutionExponent);
        for (InterfaceFrame frame : frames) {
            long units = frame.tsNanos() / NANOS_PER_SECOND * unitsPerSecond
                    + frame.tsNanos() % NANOS_PER_SECOND * unitsPerSecond / NANOS_PER_SECOND;
            out.write(block(6, ByteBuffer.allocate(20 + frame.data().length).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(frame.interfaceId()).putInt((int) (units >>> 32)).putInt((int) units)
                    .putInt(frame.data().length).putInt(frame.data().length).put(frame.data()).array()));
        }
        Files.write(file, out.toByteArray());
        return file;
    }

    private static byte[] block(int type, byte[] body) {
        int padded = (body.length + 3) & ~3;
        int total = 12 + padded;
        return ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(type).putInt(total).put(body).position(8 + padded).putInt(total).array();
    }

    /**
     * Skips the calling test when the native libpcap cannot be loaded (Npcap on Windows,
     * libpcap0.8 on Linux). Goes through {@link CaptureReaders} so the reader's own library
     * lookup (Npcap before a legacy WinPcap) is what gets tested.
     */
    public static void assumeLibpcap(Path tempDir) throws IOException {
        Path probe = writePcap(tempDir.resolve("probe.pcap"), LINKTYPE_ETHERNET, List.of());
        try (CaptureReader ignored = CaptureReaders.open(probe)) {
            // opened fine
        } catch (IOException e) {
            Assumptions.assumeFalse(String.valueOf(e.getMessage()).startsWith("libpcap is not available"),
                    "native libpcap not installed: " + e.getMessage());
            throw e;
        }
    }
}
