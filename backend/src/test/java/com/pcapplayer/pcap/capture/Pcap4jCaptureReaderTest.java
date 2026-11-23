package com.pcapplayer.pcap.capture;

import com.pcapplayer.testsupport.CaptureFiles;
import com.pcapplayer.testsupport.CaptureFiles.Frame;
import com.pcapplayer.testsupport.CaptureFiles.InterfaceFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.pcapplayer.testsupport.CaptureFiles.LINKTYPE_ETHERNET;
import static com.pcapplayer.testsupport.CaptureFiles.LINKTYPE_LINUX_SLL2;
import static com.pcapplayer.testsupport.Frames.ethernet;
import static com.pcapplayer.testsupport.Frames.ipv4;
import static com.pcapplayer.testsupport.Frames.pattern;
import static com.pcapplayer.testsupport.Frames.udp;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reading capture files through libpcap (needs the native library; skipped without it). */
class Pcap4jCaptureReaderTest {

    private static final long T0 = 1_700_000_000_000_000_000L;
    private static final byte[] FRAME_A = ethernet(ipv4("10.0.0.1", "10.0.0.2", 17, udp(1000, 2000, pattern(30, 1))));
    private static final byte[] FRAME_B = ethernet(ipv4("10.0.0.1", "10.0.0.2", 17, udp(1000, 2000, pattern(40, 2))));

    @TempDir
    Path dir;

    @BeforeEach
    void requireLibpcap() throws IOException {
        CaptureFiles.assumeLibpcap(dir);
    }

    @Test
    void readsLittleEndianMicrosecondPcap() throws IOException {
        Path file = CaptureFiles.writePcap(dir.resolve("le_us.pcap"), LINKTYPE_ETHERNET, ByteOrder.LITTLE_ENDIAN, false,
                List.of(new Frame(T0 + 123_456_000, FRAME_A), new Frame(T0 + 1_000_001_000, FRAME_B)));

        List<RawFrame> frames = readAll(file);

        assertEquals(2, frames.size());
        assertEquals(T0 + 123_456_000, frames.get(0).tsNanos());
        assertEquals(T0 + 1_000_001_000, frames.get(1).tsNanos());
        assertEquals(LINKTYPE_ETHERNET, frames.get(0).linkType());
        assertArrayEquals(FRAME_A, frames.get(0).data());
        assertArrayEquals(FRAME_B, frames.get(1).data());
        assertFalse(frames.get(0).truncated());
    }

    @Test
    void readsBigEndianNanosecondPcapWithFullPrecision() throws IOException {
        Path file = CaptureFiles.writePcap(dir.resolve("be_ns.pcap"), LINKTYPE_ETHERNET, ByteOrder.BIG_ENDIAN, true,
                List.of(new Frame(T0 + 123_456_789, FRAME_A)));

        try (CaptureReader reader = CaptureReaders.open(file)) {
            RawFrame frame = reader.next();
            assertEquals(T0 + 123_456_789, frame.tsNanos());
            assertArrayEquals(FRAME_A, frame.data());
            assertEquals("pcap", reader.format());
        }
    }

    @Test
    void readsPcapngWithNanosecondResolution() throws IOException {
        Path file = CaptureFiles.writePcapng(dir.resolve("ns.pcapng"), List.of(LINKTYPE_ETHERNET), 9,
                List.of(new InterfaceFrame(0, T0 + 987_654_321, FRAME_A), new InterfaceFrame(0, T0 + 2_000_000_007, FRAME_B)));

        try (CaptureReader reader = CaptureReaders.open(file)) {
            assertEquals("pcapng", reader.format());
            RawFrame first = reader.next();
            RawFrame second = reader.next();
            assertNull(reader.next());
            assertEquals(T0 + 987_654_321, first.tsNanos());
            assertEquals(T0 + 2_000_000_007, second.tsNanos());
            assertArrayEquals(FRAME_A, first.data());
            assertArrayEquals(FRAME_B, second.data());
            assertFalse(reader.endedAbruptly());
        }
    }

    @Test
    void readsPcapngWithMicrosecondResolution() throws IOException {
        Path file = CaptureFiles.writePcapng(dir.resolve("us.pcapng"), List.of(LINKTYPE_ETHERNET), 6,
                List.of(new InterfaceFrame(0, T0 + 654_321_000, FRAME_A)));

        assertEquals(T0 + 654_321_000, readAll(file).get(0).tsNanos());
    }

    @Test
    void readsPcapngWithSeveralInterfacesOfTheSameLinkType() throws IOException {
        Path file = CaptureFiles.writePcapng(dir.resolve("two_eth.pcapng"), List.of(LINKTYPE_ETHERNET, LINKTYPE_ETHERNET), 9,
                List.of(new InterfaceFrame(0, T0, FRAME_A), new InterfaceFrame(1, T0 + 1, FRAME_B)));

        assertEquals(2, readAll(file).size());
    }

    @Test
    void reportsSnaplenTruncationThroughTheOriginalLength() throws IOException {
        byte[] captured = java.util.Arrays.copyOf(FRAME_B, 50);
        Path file = CaptureFiles.writePcap(dir.resolve("snaplen.pcap"), LINKTYPE_ETHERNET,
                List.of(new Frame(T0, captured, FRAME_B.length)));

        RawFrame frame = readAll(file).get(0);

        assertEquals(50, frame.data().length);
        assertEquals(FRAME_B.length, frame.origLen());
        assertTrue(frame.truncated());
    }

    @Test
    void partialLastRecordEndsAbruptlyAfterTheCompleteFrames() throws IOException {
        Path file = CaptureFiles.writePcap(dir.resolve("cut.pcap"), LINKTYPE_ETHERNET,
                List.of(new Frame(T0, FRAME_A), new Frame(T0 + 1000, FRAME_B)));
        byte[] bytes = Files.readAllBytes(file);
        Files.write(file, java.util.Arrays.copyOf(bytes, bytes.length - 5));

        try (CaptureReader reader = CaptureReaders.open(file)) {
            assertArrayEquals(FRAME_A, reader.next().data());
            assertNull(reader.next());
            assertNull(reader.next(), "stays at end of file");
            assertTrue(reader.endedAbruptly());
        }
    }

    @Test
    void rejectsAFileThatIsNotACapture() throws IOException {
        Path file = Files.write(dir.resolve("garbage.pcap"), "this is not a capture file at all".getBytes());

        InvalidCaptureException e = assertThrows(InvalidCaptureException.class, () -> CaptureReaders.open(file));
        assertTrue(e.getMessage().startsWith("Not a pcap/pcapng file"), e.getMessage());
    }

    @Test
    void rejectsAnEmptyFile() throws IOException {
        Path file = Files.write(dir.resolve("empty.pcap"), new byte[0]);

        InvalidCaptureException e = assertThrows(InvalidCaptureException.class, () -> CaptureReaders.open(file));
        assertEquals("Empty file", e.getMessage());
    }

    @Test
    void rejectsPcapngMixingLinkTypesWithAClearMessage() throws IOException {
        Path file = CaptureFiles.writePcapng(dir.resolve("mixed.pcapng"), List.of(LINKTYPE_ETHERNET, LINKTYPE_LINUX_SLL2), 9,
                List.of(new InterfaceFrame(0, T0, FRAME_A)));

        InvalidCaptureException e = assertThrows(InvalidCaptureException.class, () -> readAll(file));
        assertTrue(e.getMessage().contains("different link types"), e.getMessage());
    }

    private static List<RawFrame> readAll(Path file) throws IOException {
        List<RawFrame> frames = new ArrayList<>();
        try (CaptureReader reader = CaptureReaders.open(file)) {
            RawFrame frame;
            while ((frame = reader.next()) != null) frames.add(frame);
        }
        return frames;
    }
}
