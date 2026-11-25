package com.pcapplayer.pcapProcessing.capture;

import com.pcapplayer.testSupport.CaptureFiles;
import com.pcapplayer.testSupport.Gzip;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PcapInputOpenerTest {

    @TempDir
    Path dir;

    private byte[] pcapBytes() throws IOException {
        Path file = CaptureFiles.writePcap(dir.resolve("source.pcap"), CaptureFiles.LINKTYPE_ETHERNET,
                List.of(new CaptureFiles.Frame(1_000_000_000L, new byte[60])));
        return Files.readAllBytes(file);
    }

    private OpenedPcap open(byte[] input, String name) throws IOException {
        return PcapInputOpener.open(new ByteArrayInputStream(input), name, dir.resolve("out.pcap"));
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    @Test
    void plainPcapIsCopiedAsIs() throws Exception {
        byte[] pcap = pcapBytes();

        OpenedPcap opened = open(pcap, "a.pcap");

        assertEquals(EInputKind.PCAP, opened.kind());
        assertFalse(opened.gzipped());
        assertEquals("a.pcap", opened.name());
        assertArrayEquals(pcap, Files.readAllBytes(opened.path()));
        assertEquals(pcap.length, opened.size());
        assertEquals(sha256(pcap), opened.sha256());
    }

    @Test
    void gzippedPcapIsDecompressedAndLosesTheGzSuffix() throws Exception {
        byte[] pcap = pcapBytes();

        OpenedPcap opened = open(Gzip.gzip(pcap), "capture.pcap.GZ");

        assertTrue(opened.gzipped());
        assertEquals(EInputKind.PCAP, opened.kind());
        assertEquals("capture.pcap", opened.name());
        assertArrayEquals(pcap, Files.readAllBytes(opened.path()));
        assertEquals(sha256(pcap), opened.sha256(), "the hash is of the decompressed content");
    }

    @Test
    void gzippedWithoutGzNameIsStillDetectedByItsBytes() throws Exception {
        OpenedPcap opened = open(Gzip.gzip(pcapBytes()), "capture.bin");

        assertTrue(opened.gzipped());
        assertEquals("capture.bin", opened.name());
        assertEquals(EInputKind.PCAP, opened.kind());
    }

    @Test
    void gzNameThatIsNotGzippedKeepsItsName() throws Exception {
        OpenedPcap opened = open(pcapBytes(), "capture.pcap.gz");

        assertFalse(opened.gzipped());
        assertEquals("capture.pcap.gz", opened.name());
        assertEquals(EInputKind.PCAP, opened.kind());
    }

    @Test
    void gzippedTextIsNotAPcap() throws Exception {
        OpenedPcap opened = open(Gzip.gzip("hello, not a capture".getBytes(StandardCharsets.US_ASCII)), "notes.txt.gz");

        assertTrue(opened.gzipped());
        assertEquals(EInputKind.NOT_A_PCAP, opened.kind());
        assertNull(opened.path());
        assertFalse(Files.exists(dir.resolve("out.pcap")), "nothing is written for a non-pcap");
    }

    @Test
    void plainTextAndEmptyFilesAreNotPcaps() throws Exception {
        assertEquals(EInputKind.NOT_A_PCAP, open("text".getBytes(StandardCharsets.US_ASCII), "a.pcap").kind());
        assertEquals(EInputKind.NOT_A_PCAP, open(new byte[0], "empty.pcap").kind());
        assertEquals(EInputKind.NOT_A_PCAP, open(new byte[]{(byte) 0xA1, (byte) 0xB2}, "short").kind());
    }

    @Test
    void corruptGzipFailsAndLeavesNoFile() throws Exception {
        byte[] gzipped = Gzip.gzip(pcapBytes());
        byte[] cut = Arrays.copyOf(gzipped, gzipped.length - 12); // drops the trailer and some data

        assertThrows(CorruptInputException.class, () -> open(cut, "cut.pcap.gz"));
        assertFalse(Files.exists(dir.resolve("out.pcap")));
    }

    @Test
    void garbageAfterTheGzipMagicIsCorrupt() {
        byte[] garbage = {0x1F, (byte) 0x8B, 0x08, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 4, 5, 6};

        assertThrows(CorruptInputException.class, () -> open(garbage, "bad.gz"));
    }

    @Test
    void recognisesEveryMagicNumber() {
        assertEquals(EInputKind.PCAP, PcapInputOpener.kindOf(new byte[]{(byte) 0xD4, (byte) 0xC3, (byte) 0xB2, (byte) 0xA1}));
        assertEquals(EInputKind.PCAP, PcapInputOpener.kindOf(new byte[]{(byte) 0xA1, (byte) 0xB2, (byte) 0xC3, (byte) 0xD4}));
        assertEquals(EInputKind.PCAP, PcapInputOpener.kindOf(new byte[]{0x4D, 0x3C, (byte) 0xB2, (byte) 0xA1}));
        assertEquals(EInputKind.PCAP, PcapInputOpener.kindOf(new byte[]{(byte) 0xA1, (byte) 0xB2, 0x3C, 0x4D}));
        assertEquals(EInputKind.PCAPNG, PcapInputOpener.kindOf(new byte[]{0x0A, 0x0D, 0x0D, 0x0A}));
        assertEquals(EInputKind.NOT_A_PCAP, PcapInputOpener.kindOf(new byte[]{1, 2, 3, 4}));
    }
}
