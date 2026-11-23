package com.pcapplayer.pcap.extract;

import com.pcapplayer.pcap.diagnostics.Warning;
import com.pcapplayer.pcap.script.EConnectionMode;
import com.pcapplayer.pcap.script.ScriptStep;
import com.pcapplayer.testsupport.CaptureFiles;
import com.pcapplayer.testsupport.CaptureFiles.Frame;
import com.pcapplayer.testsupport.Frames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageExtractorTest {

    private static final long T0 = TimeUnit.SECONDS.toNanos(1_700_000_000L);
    private static final long MS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final String CLIENT = "10.0.0.1";
    private static final String SERVER = "10.0.0.2";
    private static final int SERVICE_PORT = 5000;
    private static final int CLIENT_PORT = 40000;
    private static final byte[] NO_DATA = new byte[0];

    @TempDir
    Path tempDir;

    private final MessageExtractor extractor = new MessageExtractor(ExtractionSettings.defaults());

    @BeforeEach
    void requireLibpcap() throws IOException {
        CaptureFiles.assumeLibpcap(tempDir);
    }

    // ------------------------------------------------------------------ TCP

    @Test
    void singleTcpConnectionReplaysTheClientBytesInOrder() throws IOException {
        ExtractionResult result = extractTcp(List.of(
                toServer(0, CLIENT_PORT, 100, 0, Frames.SYN, NO_DATA),
                toClient(1, CLIENT_PORT, 900, 101, Frames.SYN | Frames.ACK, NO_DATA),
                toServer(2, CLIENT_PORT, 101, 901, Frames.ACK | Frames.PSH, text("hello")),
                toClient(3, CLIENT_PORT, 901, 106, Frames.ACK, NO_DATA),
                toServer(4, CLIENT_PORT, 106, 901, Frames.ACK | Frames.PSH, text("abc")),
                toClient(5, CLIENT_PORT, 901, 109, Frames.ACK, NO_DATA),
                toServer(6, CLIENT_PORT, 109, 901, Frames.FIN | Frames.ACK, NO_DATA),
                toClient(7, CLIENT_PORT, 901, 110, Frames.FIN | Frames.ACK, NO_DATA)));

        assertEquals(1, result.connections());
        assertEquals(8, result.script().totalBytes());
        assertArrayEquals(text("helloabc"), sentBytes(result));
        assertEquals(0, count(result, ScriptStep.Reconnect.class));
        assertEquals(T0 + 2 * MS, result.firstMessageNanos());
        assertTrue(result.warnings().isEmpty(), result.warnings().toString());
    }

    @Test
    void sequentialConnectionsAreSeparatedByAReconnect() throws IOException {
        ExtractionResult result = extractTcp(List.of(
                toServer(0, CLIENT_PORT, 100, 0, Frames.SYN, NO_DATA),
                toServer(2, CLIENT_PORT, 101, 0, Frames.ACK | Frames.PSH, text("one")),
                toServer(6, CLIENT_PORT, 104, 0, Frames.FIN | Frames.ACK, NO_DATA),
                toClient(7, CLIENT_PORT, 900, 105, Frames.FIN | Frames.ACK, NO_DATA),
                toServer(20, CLIENT_PORT + 1, 500, 0, Frames.SYN, NO_DATA),
                toServer(22, CLIENT_PORT + 1, 501, 0, Frames.ACK | Frames.PSH, text("two"))));

        assertEquals(2, result.connections());
        assertArrayEquals(text("onetwo"), sentBytes(result));
        List<ScriptStep.Reconnect> reconnects = steps(result, ScriptStep.Reconnect.class);
        assertEquals(1, reconnects.size());
        assertEquals(7 * MS, reconnects.get(0).offsetNanos());
        assertEquals(20 * MS, reconnects.get(0).connectOffsetNanos());
    }

    @Test
    void tupleReusedWithoutSynStartsANewConnection() throws IOException {
        ExtractionResult result = extractTcp(List.of(
                toServer(0, CLIENT_PORT, 100, 0, Frames.ACK | Frames.PSH, text("one")),
                toServer(5, CLIENT_PORT, 103, 0, Frames.FIN | Frames.ACK, NO_DATA),
                toClient(6, CLIENT_PORT, 900, 104, Frames.FIN | Frames.ACK, NO_DATA),
                toServer(10, CLIENT_PORT, 7000, 0, Frames.ACK | Frames.PSH, text("two"))));

        assertEquals(2, result.connections());
        assertEquals(1, count(result, ScriptStep.Reconnect.class));
        assertArrayEquals(text("onetwo"), sentBytes(result));
    }

    @Test
    void overlappingConnectionsShareOneReplayConnection() throws IOException {
        ExtractionResult result = extractTcp(List.of(
                toServer(0, CLIENT_PORT, 100, 0, Frames.SYN, NO_DATA),
                toServer(1, CLIENT_PORT, 101, 0, Frames.ACK | Frames.PSH, text("aa")),
                toServer(2, CLIENT_PORT + 1, 500, 0, Frames.SYN, NO_DATA),
                toServer(3, CLIENT_PORT + 1, 501, 0, Frames.ACK | Frames.PSH, text("bb")),
                toServer(4, CLIENT_PORT, 103, 0, Frames.FIN | Frames.ACK, NO_DATA),
                toClient(4, CLIENT_PORT, 900, 104, Frames.FIN | Frames.ACK, NO_DATA), // A lives past B's start
                toServer(5, CLIENT_PORT + 1, 503, 0, Frames.FIN | Frames.ACK, NO_DATA)));

        assertEquals(2, result.connections());
        assertEquals(0, count(result, ScriptStep.Reconnect.class));
        assertArrayEquals(text("aabb"), sentBytes(result));
        assertEquals(2L, warning(result, "OVERLAPPING_CONNECTIONS").params().get("count"));
    }

    @Test
    void missingStreamBytesAreReportedAsAGap() throws IOException {
        ExtractionResult result = extractTcp(List.of(
                toServer(0, CLIENT_PORT, 100, 0, Frames.SYN, NO_DATA),
                toServer(1, CLIENT_PORT, 101, 0, Frames.ACK | Frames.PSH, text("12345")),
                toServer(2, CLIENT_PORT, 116, 0, Frames.ACK | Frames.PSH, text("67890"))));

        Warning gaps = warning(result, "TCP_GAPS");
        assertEquals(1L, gaps.params().get("count"));
        assertEquals(10L, gaps.params().get("bytes"));
        List<ScriptStep.Gap> gapSteps = steps(result, ScriptStep.Gap.class);
        assertEquals(1, gapSteps.size());
        assertEquals(10, gapSteps.get(0).bytes());
        assertArrayEquals(text("1234567890"), sentBytes(result));
    }

    @Test
    void serviceSidePacketsAloneAreIgnored() throws IOException {
        ExtractionResult result = extractTcp(List.of(
                toClient(0, CLIENT_PORT, 900, 0, Frames.ACK | Frames.PSH, text("reply"))));

        assertEquals(0, result.connections());
        assertEquals(0, result.messageCount());
    }

    @Test
    void otherProtocolPacketsOnTheSelectedPortAreCounted() throws IOException {
        ExtractionResult result = extractTcp(List.of(
                udpToServer(0, text("datagram"))));

        assertEquals(0, result.messageCount());
        assertEquals(1, result.otherProtocolPackets());
    }

    // ------------------------------------------------------------------ UDP

    @Test
    void udpDatagramsAreSentAndDuplicatesDropped() throws IOException {
        ExtractionResult result = extract(EConnectionMode.UDP_SERVER, List.of(
                udpToServer(0, text("first")),
                udpToServer(0, text("first")), // captured twice within the duplicate window
                udpToServer(50, text("second"))));

        assertEquals(2, result.messageCount());
        assertArrayEquals(text("firstsecond"), sentBytes(result));
        assertEquals(1L, warning(result, "UDP_DUPLICATES").params().get("count"));
        assertEquals(50 * MS, steps(result, ScriptStep.Send.class).get(1).offsetNanos());
    }

    // ------------------------------------------------------------------ helpers

    private ExtractionResult extractTcp(List<Frame> frames) throws IOException {
        return extract(EConnectionMode.TCP_SERVER, frames);
    }

    private ExtractionResult extract(EConnectionMode mode, List<Frame> frames) throws IOException {
        Path file = CaptureFiles.writePcap(tempDir.resolve("capture.pcap"), CaptureFiles.LINKTYPE_ETHERNET, frames);
        ExtractionFilter filter = new ExtractionFilter(mode, Set.of(SERVICE_PORT), null, T0, T0 + TimeUnit.SECONDS.toNanos(10));
        return extractor.extract(file, filter);
    }

    private static Frame toServer(long ms, int clientPort, long seq, long ack, int flags, byte[] data) {
        return tcpFrame(ms, CLIENT, clientPort, SERVER, SERVICE_PORT, seq, ack, flags, data);
    }

    private static Frame toClient(long ms, int clientPort, long seq, long ack, int flags, byte[] data) {
        return tcpFrame(ms, SERVER, SERVICE_PORT, CLIENT, clientPort, seq, ack, flags, data);
    }

    private static Frame tcpFrame(long ms, String src, int srcPort, String dst, int dstPort,
                                  long seq, long ack, int flags, byte[] data) {
        byte[] segment = Frames.tcp(srcPort, dstPort, seq, ack, flags, data);
        return new Frame(T0 + ms * MS, Frames.ethernet(Frames.ipv4(src, dst, Frames.PROTO_TCP, segment)));
    }

    private static Frame udpToServer(long ms, byte[] data) {
        byte[] datagram = Frames.udp(CLIENT_PORT, SERVICE_PORT, data);
        return new Frame(T0 + ms * MS, Frames.ethernet(Frames.ipv4(CLIENT, SERVER, Frames.PROTO_UDP, datagram)));
    }

    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] sentBytes(ExtractionResult result) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        steps(result, ScriptStep.Send.class).forEach(send -> out.writeBytes(send.data()));
        return out.toByteArray();
    }

    private static <T extends ScriptStep> List<T> steps(ExtractionResult result, Class<T> type) {
        return result.script().steps().stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static long count(ExtractionResult result, Class<? extends ScriptStep> type) {
        return steps(result, type).size();
    }

    private static Warning warning(ExtractionResult result, String code) {
        return result.warnings().stream().filter(w -> w.code().equals(code)).findFirst()
                .orElseThrow(() -> new AssertionError("no " + code + " in " + result.warnings()));
    }
}
