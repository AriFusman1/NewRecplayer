package com.pcapplayer.pcapProcessing.capture;

import org.pcap4j.core.NotOpenException;
import org.pcap4j.core.PcapHandle.TimestampPrecision;
import org.pcap4j.core.PcapHandle;
import org.pcap4j.core.PcapNativeException;
import org.pcap4j.core.Pcaps;
import org.pcap4j.util.ByteArrays;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Reads pcap and pcapng files with libpcap ({@code pcap_open_offline}) through pcap4j. libpcap
 * handles both byte orders, µs/ns resolution and pcapng {@code if_tsresol}/{@code if_tsoffset};
 * timestamps are requested at nanosecond precision.
 * <p>
 * Needs the native libpcap at runtime: {@code libpcap0.8} on Linux, Npcap on Windows.
 */
public class Pcap4jCaptureReader implements CaptureReader {

    /** Block type of a pcapng Section Header Block; the same in both byte orders. */
    private static final int PCAPNG_SECTION_HEADER_MAGIC = 0x0A0D0D0A;

    static {
        // Npcap installs its libpcap in System32\Npcap; a legacy WinPcap wpcap.dll in System32 would
        // otherwise be loaded first. Must run before any pcap4j class loads the native library.
        boolean windows = System.getProperty("os.name", "").startsWith("Windows");

        if (windows && System.getProperty("jna.library.path") == null) {
            Path npcapDir = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", "Npcap");

            if (Files.isRegularFile(npcapDir.resolve("wpcap.dll"))) {
                System.setProperty("jna.library.path", npcapDir.toString());
            }
        }
    }

    private final PcapHandle handle;
    private final String format;
    private final int linkType;
    private boolean endedAbruptly;
    private boolean endOfFile;

    Pcap4jCaptureReader(Path file) throws IOException {
        format = detectFormat(file);
        String filePath = file.toAbsolutePath().toString();

        try {
            handle = openOffline(filePath);
        } catch (PcapNativeException e) {
            throw new InvalidCaptureException("Not a pcap/pcapng file: " + e.getMessage());
        } catch (LinkageError e) { // UnsatisfiedLinkError, ExceptionInInitializerError, NoClassDefFoundError
            throw new IOException("libpcap is not available (install libpcap on Linux or Npcap on Windows): " + e.getMessage(), e);
        }
        linkType = handle.getDlt().value();
    }

    /** Nanosecond timestamps need libpcap 1.5+; older libraries fall back to microseconds. */
    private static PcapHandle openOffline(String filePath) throws PcapNativeException {
        try {
            return Pcaps.openOffline(filePath, TimestampPrecision.NANO);
        } catch (PcapNativeException e) {
            if (String.valueOf(e.getMessage()).contains("tstamp_precision is not supported")) {
                return Pcaps.openOffline(filePath);
            }
            throw e;
        }
    }

    /** libpcap reads both formats transparently; the magic number is only sniffed to report which one it is. */
    private static String detectFormat(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] magic = in.readNBytes(ByteArrays.INT_SIZE_IN_BYTES);

            if (magic.length == 0) {
                throw new InvalidCaptureException("Empty file");
            }
            boolean pcapng = magic.length == ByteArrays.INT_SIZE_IN_BYTES
                    && ByteArrays.getInt(magic, 0) == PCAPNG_SECTION_HEADER_MAGIC;
            return pcapng ? "pcapng" : "pcap";
        }
    }

    @Override
    public RawFrame next() throws IOException {
        if (endOfFile) {
            return null;
        }

        try {
            byte[] packetData = handle.getNextRawPacketEx();
            Integer originalLength = handle.getOriginalLength();
            int wireLength = originalLength == null ? packetData.length : Math.max(originalLength, packetData.length);
            return new RawFrame(toEpochNanos(handle.getTimestamp()), linkType, packetData, wireLength);
        } catch (EOFException e) {
            endOfFile = true;
            return null;
        } catch (PcapNativeException e) {
            String error = String.valueOf(e.getMessage());
            // libpcap reports a partial last record as "truncated dump file; ..."
            if (error.contains("truncated")) {
                endOfFile = true;
                endedAbruptly = true;
                return null;
            }

            if (error.contains("different from the type of the first interface")) {
                throw new InvalidCaptureException(
                        "Unsupported capture: libpcap cannot read a pcapng file whose interfaces have different link types");
            }
            throw new InvalidCaptureException("Corrupt capture: " + error);
        } catch (TimeoutException | NotOpenException e) {
            throw new IOException(e);
        }
    }

    private static long toEpochNanos(Timestamp timestamp) {
        if (timestamp == null) {
            return 0;
        }
        Instant instant = timestamp.toInstant();
        return TimeUnit.SECONDS.toNanos(instant.getEpochSecond()) + instant.getNano();
    }

    @Override
    public String format() {
        return format;
    }

    @Override
    public boolean endedAbruptly() {
        return endedAbruptly;
    }

    @Override
    public void close() {
        handle.close();
    }
}
