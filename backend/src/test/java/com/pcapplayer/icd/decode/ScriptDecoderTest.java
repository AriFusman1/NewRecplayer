package com.pcapplayer.icd.decode;

import com.pcapplayer.icd.analysis.CollectingAnalyzer;
import com.pcapplayer.icd.analysis.MessageCountAnalyzer;
import com.pcapplayer.icd.model.IcdDefinition;
import com.pcapplayer.pcapProcessing.script.ReplayScript;
import com.pcapplayer.testSupport.SampleIcdMessages;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.pcapplayer.testSupport.SampleIcdMessages.STATUS_SIZE;
import static com.pcapplayer.testSupport.SampleIcdMessages.STATUS_TIMESTAMP_OFFSET;
import static com.pcapplayer.testSupport.SampleIcdMessages.concat;
import static com.pcapplayer.testSupport.SampleIcdMessages.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Application-level reassembly: messages across packets, several per packet, and the error cases. */
class ScriptDecoderTest {

    private static final long RANGE_START = 1_700_000_000_000_000_000L;
    private static final long MS = TestScript.MS;

    private final IcdDefinition icd = SampleIcdMessages.icd();

    @Test
    void aMessageSplitAcrossThreePackets() {
        byte[] message = status(1, 2, 3);
        ReplayScript script = new TestScript()
                .send(0, Arrays.copyOfRange(message, 0, 10))   // part of the header
                .send(0, Arrays.copyOfRange(message, 10, 28))  // splits timestampMs (bytes 24..31)
                .send(0, Arrays.copyOfRange(message, 28, STATUS_SIZE))
                .build();

        List<DecodedMessage> messages = ScriptDecoder.decodeAll(script, RANGE_START, icd);

        assertEquals(1, messages.size());
        DecodedMessage decoded = messages.get(0);
        assertEquals(3L, decoded.fields().get("timestampMs").value());
        assertEquals(0, decoded.firstStep());
        assertEquals(2, decoded.lastStep());
        assertEquals(RANGE_START, decoded.captureTsNanos(), "the time of the first packet");
        assertEquals(List.of(new ByteSpan(1, 14, 4), new ByteSpan(2, 0, 4)), decoded.location("timestampMs"),
                "a field across two packets maps to both");
        assertEquals(List.of(new ByteSpan(0, 8, 2), new ByteSpan(1, 0, 2)), decoded.location("header.sendTime"));
        assertEquals("TCP 0.0.0.1:1000 → 0.0.0.2:2000", decoded.flow().describe());
    }

    @Test
    void severalMessagesInOnePacketAndOneEndingMidPacket() {
        byte[] first = status(1, 0, 10);
        byte[] second = status(2, 0, 20);
        byte[] third = status(3, 0, 30);
        byte[] all = concat(first, second, third);
        ReplayScript script = new TestScript()
                .send(0, Arrays.copyOfRange(all, 0, 2 * STATUS_SIZE + 5))
                .send(0, Arrays.copyOfRange(all, 2 * STATUS_SIZE + 5, all.length))
                .build();

        List<DecodedMessage> messages = ScriptDecoder.decodeAll(script, RANGE_START, icd);

        assertEquals(List.of(10L, 20L, 30L), messages.stream().map(message -> message.fields().get("timestampMs").value()).toList());
        assertEquals(List.of(new ByteSpan(0, STATUS_SIZE + STATUS_TIMESTAMP_OFFSET, 8)), messages.get(1).location("timestampMs"));
        assertEquals(List.of(new ByteSpan(0, 2 * STATUS_SIZE, 5), new ByteSpan(1, 0, STATUS_SIZE - 5)), messages.get(2).pieces());
        assertEquals(RANGE_START, messages.get(2).captureTsNanos(), "the third message starts in the first packet");
    }

    @Test
    void aTruncatedLastMessageIsCountedAndTheRestDecodes() {
        ReplayScript script = new TestScript()
                .send(0, status(1, 0, 10))
                .send(0, Arrays.copyOf(status(2, 0, 20), 20))
                .build();

        DecodeStats stats = ScriptDecoder.decode(script, RANGE_START, icd, message -> { });

        assertEquals(1, stats.messages());
        assertEquals(1, stats.count(EDecodeProblem.TRUNCATED));
        assertEquals(20, stats.skippedBytes());
    }

    @Test
    void anUnknownOpcodeIsSkippedByItsLengthAndTheStreamStaysAligned() {
        byte[] unknown = SampleIcdMessages.withHeader(0x99, 0, 0, new byte[8], 20);
        ReplayScript script = new TestScript().send(0, concat(status(1, 0, 10), unknown, status(2, 0, 20))).build();
        List<DecodedMessage> messages = new ArrayList<>();

        DecodeStats stats = ScriptDecoder.decode(script, RANGE_START, icd, messages::add);

        assertEquals(1, stats.count(EDecodeProblem.UNKNOWN_OPCODE));
        assertEquals(List.of(10L, 20L), messages.stream().map(message -> message.fields().get("timestampMs").value()).toList());
    }

    @Test
    void anImpossibleLengthResyncsAtTheNextPacket() {
        byte[] garbage = new byte[30];
        Arrays.fill(garbage, (byte) 0xFF); // length 0xFFFF > maxMessageBytes
        ReplayScript script = new TestScript()
                .send(0, concat(garbage, Arrays.copyOf(status(1, 0, 10), 5)))
                .send(0, status(2, 0, 20))
                .build();
        List<DecodedMessage> messages = new ArrayList<>();

        DecodeStats stats = ScriptDecoder.decode(script, RANGE_START, icd, messages::add);

        assertEquals(1, stats.count(EDecodeProblem.INVALID_LENGTH));
        assertEquals(35, stats.skippedBytes(), "the rest of the first packet");
        assertEquals(1, messages.size());
        assertEquals(20L, messages.get(0).fields().get("timestampMs").value());
        assertEquals(1, messages.get(0).firstStep());
    }

    @Test
    void aMessageTooShortForItsDefinitionIsAParseErrorButFramingContinues() {
        byte[] shortStatus = SampleIcdMessages.withHeader(1, 0, 0, new byte[5], 17);
        ReplayScript script = new TestScript().send(0, concat(shortStatus, status(2, 0, 20))).build();
        List<DecodedMessage> messages = new ArrayList<>();

        DecodeStats stats = ScriptDecoder.decode(script, RANGE_START, icd, messages::add);

        assertEquals(1, stats.count(EDecodeProblem.PARSE_ERROR));
        assertEquals(1, messages.size());
    }

    @Test
    void aCaptureGapDropsTheMessageInProgress() {
        byte[] message = status(1, 0, 10);
        ReplayScript script = new TestScript()
                .send(0, Arrays.copyOf(message, 20))
                .gap(0)
                .send(0, status(2, 0, 20))
                .build();
        List<DecodedMessage> messages = new ArrayList<>();

        DecodeStats stats = ScriptDecoder.decode(script, RANGE_START, icd, messages::add);

        assertEquals(1, stats.count(EDecodeProblem.TRUNCATED));
        assertEquals(1, messages.size());
        assertEquals(20L, messages.get(0).fields().get("timestampMs").value());
    }

    @Test
    void flowsAreReassembledSeparately() {
        byte[] flow0 = status(1, 0, 10);
        byte[] flow1 = status(2, 0, 20);
        ReplayScript script = new TestScript()
                .send(0, Arrays.copyOf(flow0, 15))
                .send(1, Arrays.copyOf(flow1, 15))
                .send(0, Arrays.copyOfRange(flow0, 15, STATUS_SIZE))
                .send(1, Arrays.copyOfRange(flow1, 15, STATUS_SIZE))
                .build();

        List<DecodedMessage> messages = ScriptDecoder.decodeAll(script, RANGE_START, icd);

        assertEquals(2, messages.size());
        assertEquals(10L, messages.get(0).fields().get("timestampMs").value());
        assertEquals(List.of(0, 2), List.of(messages.get(0).firstStep(), messages.get(0).lastStep()));
        assertEquals(20L, messages.get(1).fields().get("timestampMs").value());
        assertEquals(1, messages.get(1).flowId());
        assertEquals(RANGE_START + MS, messages.get(1).captureTsNanos());
    }

    @Test
    void analyzersSeeEveryMessageAndTheEnd() {
        byte[] track = SampleIcdMessages.track(1, 0, 0, 0, 0);
        ReplayScript script = new TestScript().send(0, concat(status(1, 0, 1), track, status(2, 0, 2))).build();
        MessageCountAnalyzer counts = new MessageCountAnalyzer();
        CollectingAnalyzer tracks = new CollectingAnalyzer(10, 2L);

        ScriptDecoder.decode(script, RANGE_START, icd, counts, tracks);

        assertEquals(2, counts.count(1));
        assertEquals(1, counts.count(2));
        assertEquals(List.of("Status", "Track"), counts.counts().stream().map(MessageCountAnalyzer.OpcodeCount::name).toList());
        assertEquals(1, tracks.messages().size());
        assertTrue(tracks.messages().get(0).fields().containsKey("gpsTime"));
    }
}
