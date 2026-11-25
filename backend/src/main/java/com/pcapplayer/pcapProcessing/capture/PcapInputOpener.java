package com.pcapplayer.pcapProcessing.capture;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

/**
 * Turns any input (a pcap, a pcapng, or either of them gzipped) into a plain capture file on disk.
 * <p>
 * Everything is decided by the bytes, never by the file name:
 * <ol>
 *   <li>{@code 1F 8B} at the start means gzip: the rest is read through {@link GZIPInputStream}.</li>
 *   <li>The first four (decompressed) bytes must be a pcap or pcapng magic number. Otherwise the
 *       input is {@link EInputKind#NOT_A_PCAP} and nothing is written.</li>
 *   <li>The content is streamed to the target file while its SHA-256 is computed, so a large
 *       file is never held in memory and is read only once.</li>
 * </ol>
 * A gzip stream that is damaged or cut off fails with {@link CorruptInputException}.
 * libpcap only reads from a file path, which is why the result is always a file.
 */
public final class PcapInputOpener {

    private static final int BUFFER_BYTES = 1 << 16;
    private static final int GZIP_MAGIC_1 = 0x1F;
    private static final int GZIP_MAGIC_2 = 0x8B;
    private static final int PCAPNG_MAGIC = 0x0A0D0D0A;

    /** Classic pcap magic numbers as read big-endian from the file: µs and ns resolution, both byte orders. */
    private static final int[] PCAP_MAGICS = {0xA1B2C3D4, 0xD4C3B2A1, 0xA1B23C4D, 0x4D3CB2A1};

    private PcapInputOpener() {
    }

    /**
     * Reads {@code raw} to the end and writes the plain capture to {@code target}. {@code raw} is
     * not closed. On any failure {@code target} is deleted.
     */
    public static OpenedPcap open(InputStream raw, String originalName, Path target) throws IOException {
        BufferedInputStream in = new BufferedInputStream(raw, BUFFER_BYTES);
        boolean gzipped = startsWithGzipMagic(in);

        try {
            InputStream content = gzipped ? new GZIPInputStream(in, BUFFER_BYTES) : in;
            byte[] magic = content.readNBytes(4);
            EInputKind kind = kindOf(magic);
            String name = cleanName(originalName, gzipped);
            if (kind == EInputKind.NOT_A_PCAP) return new OpenedPcap(null, name, gzipped, kind, 0, null);

            MessageDigest digest = sha256();
            long size;

            try (OutputStream out = new DigestOutputStream(Files.newOutputStream(target), digest)) {
                out.write(magic);
                size = magic.length + content.transferTo(out);
            }
            return new OpenedPcap(target, name, gzipped, kind, size, HexFormat.of().formatHex(digest.digest()));
        } catch (ZipException | EOFException e) {
            Files.deleteIfExists(target);
            throw new CorruptInputException("Corrupt gzip data: " + e.getMessage(), e);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(target);
            throw e;
        }
    }

    /** {@link #open(InputStream, String, Path)} for a file already on disk. */
    public static OpenedPcap open(Path source, String originalName, Path target) throws IOException {
        try (InputStream in = Files.newInputStream(source)) {
            return open(in, originalName, target);
        }
    }

    /** True if the file starts with the gzip magic bytes. */
    public static boolean isGzipped(Path file) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 2)) {
            return startsWithGzipMagic(in);
        }
    }

    /** "a.pcap.gz" → "a.pcap" when the content was gzipped; otherwise the name is kept as is. */
    public static String cleanName(String name, boolean gzipped) {
        if (name == null) return null;
        if (gzipped && name.toLowerCase(Locale.ROOT).endsWith(".gz") && name.length() > 3) return name.substring(0, name.length() - 3);
        return name;
    }

    static EInputKind kindOf(byte[] magic) {
        if (magic.length < 4) return EInputKind.NOT_A_PCAP;
        int value = ((magic[0] & 0xFF) << 24) | ((magic[1] & 0xFF) << 16) | ((magic[2] & 0xFF) << 8) | (magic[3] & 0xFF);
        if (value == PCAPNG_MAGIC) return EInputKind.PCAPNG;

        for (int pcapMagic : PCAP_MAGICS) {
            if (value == pcapMagic) return EInputKind.PCAP;
        }
        return EInputKind.NOT_A_PCAP;
    }

    /** Peeks at the first two bytes without consuming them. */
    private static boolean startsWithGzipMagic(InputStream in) throws IOException {
        in.mark(2);
        int first = in.read();
        int second = in.read();
        in.reset();
        return first == GZIP_MAGIC_1 && second == GZIP_MAGIC_2;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
