package com.pcapplayer.playback;

import com.pcapplayer.icd.decode.DecodedMessage;
import com.pcapplayer.icd.decode.ScriptDecoder;
import com.pcapplayer.icd.model.IcdDefinition;
import com.pcapplayer.icd.profile.MessageTimeFix;
import com.pcapplayer.icd.profile.TimeFieldRef;
import com.pcapplayer.icd.timefix.ETimeKind;
import com.pcapplayer.icd.timefix.FieldPatch;
import com.pcapplayer.icd.timefix.MessagePatch;
import com.pcapplayer.icd.timefix.PatchPlan;
import com.pcapplayer.pcapProcessing.script.EConnectionMode;
import com.pcapplayer.pcapProcessing.script.ReplayScript;
import com.pcapplayer.pcapProcessing.script.ScriptStep;
import com.pcapplayer.playback.model.ScriptSource;
import com.pcapplayer.recordManagement.model.Category;
import com.pcapplayer.recordManagement.model.Record;
import com.pcapplayer.recordManagement.service.RecordService;
import com.pcapplayer.recordManagement.service.TimeFixService;
import com.pcapplayer.testSupport.CaptureFiles;
import com.pcapplayer.testSupport.CaptureFiles.Frame;
import com.pcapplayer.testSupport.Frames;
import com.pcapplayer.testSupport.SampleIcdMessages;
import com.pcapplayer.testSupport.SampleIcdMessages.TargetValues;
import com.pcapplayer.testSupport.TestCatalog;
import com.pcapplayer.uploadHandling.UploadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End to end: a pcap written here goes through upload, record creation, extraction, decoding,
 * planning and the real {@link ItemPlayer}, with a fake clock and a fake transport. The sent bytes
 * are decoded again and checked.
 */
class TimeFixPlaybackTest {

    private static final Instant CAPTURE = Instant.parse("2023-11-14T22:13:20Z"); // LTime 80 000
    private static final long CAPTURE_NANOS = CAPTURE.getEpochSecond() * 1_000_000_000L;
    private static final long MS = 1_000_000L;
    private static final Instant NOW = Instant.parse("2026-11-24T23:59:59Z");    // one second before midnight
    private static final int SERVICE_PORT = 5000;

    @TempDir
    Path dir;

    private TestCatalog test;
    private final IcdDefinition icd = SampleIcdMessages.icd();

    @BeforeEach
    void setUp() throws IOException {
        CaptureFiles.assumeLibpcap(dir);
        test = new TestCatalog(dir, icd);
    }

    @Test
    void timeFieldsGetTheSendTimeAndEverythingElseIsAsRecorded() throws IOException {
        // Status: timestampMs 250 ms after its LTime sendTime. Track: gpsTime, then a target 0.5 s
        // earlier and one 1.5 s later (the second crosses midnight when replayed).
        byte[] status = SampleIcdMessages.status(1, 80_000, CAPTURE.toEpochMilli() + 250);
        byte[] track = SampleIcdMessages.track(2, 80_000, ticks(80_000_000 * MS), 0, 0,
                new TargetValues(1, 1f, 2f, (short) 3, ticks(79_999_500 * MS), (byte) 1),
                new TargetValues(2, 4f, 5f, (short) 6, ticks(80_001_500 * MS), (byte) 2));
        byte[] stream = SampleIcdMessages.concat(status, track, status);
        // TCP segments cut inside messages and fields: 30 | 25 | 40 | rest
        Record record = recordOf(stream, 30, 55, 95);
        Category category = categoryWithProfile(record.categoryId());

        ScriptSource.Prepared prepared = test.catalog.scriptSource().prepare(record.id());
        assertNotNull(prepared.timeFix());
        PatchPlan plan = prepared.timeFix().plan();
        assertEquals(3, plan.messageCount());
        assertEquals(category.icdId(), prepared.timeFix().icdId());

        TickingClock clock = new TickingClock(NOW, Duration.ofMillis(400));
        RecordingTransport transport = play(prepared, new TimeFixer(plan, prepared.script().steps(), clock));

        ReplayScript script = prepared.script();
        assertEquals(script.steps().size(), transport.sent.size());

        for (int i = 0; i < transport.sent.size(); i++) {
            assertEquals(((ScriptStep.Send) script.steps().get(i)).data().length, transport.sent.get(i).length, "packet sizes are kept");
        }
        List<DecodedMessage> messages = decode(transport.sent, script);
        assertEquals(3, clock.reads());

        Instant first = clock.read(0);   // 23:59:59.000
        assertEquals(86_399L, messages.get(0).fields().get("header.sendTime").value());
        assertEquals(first.toEpochMilli() + 250, messages.get(0).fields().get("timestampMs").value());

        Instant second = clock.read(1);  // 23:59:59.400
        long secondOfDay = (second.getEpochSecond() % 86_400) * 1_000_000_000L + second.getNano();
        assertEquals(ticks(secondOfDay), messages.get(1).fields().get("gpsTime").value());
        assertEquals(ticks(secondOfDay - 500 * MS), messages.get(1).fields().get("targets[0].seenGps").value());
        assertEquals(ticks(secondOfDay + 1_500 * MS - 86_400_000 * MS), messages.get(1).fields().get("targets[1].seenGps").value(),
                "00:00:00.9 the next day");

        assertEquals(clock.read(2).toEpochMilli() + 250, messages.get(2).fields().get("timestampMs").value());
        assertOnlyPlannedBytesChanged(script, transport.sent, plan);
    }

    @Test
    void withoutAProfileTheRecordedBytesAreSentUntouched() throws IOException {
        byte[] stream = SampleIcdMessages.concat(SampleIcdMessages.status(1, 80_000, 0), SampleIcdMessages.status(2, 80_001, 0));
        Record record = recordOf(stream, 20);
        test.catalog.icds().setCategoryIcd(record.categoryId(), icd.id()); // an ICD but no profile

        ScriptSource.Prepared prepared = test.catalog.scriptSource().prepare(record.id());

        assertNull(prepared.timeFix(), "nothing to decode");
        RecordingTransport transport = play(prepared, null);

        for (int i = 0; i < transport.sent.size(); i++) {
            assertTrue(Arrays.equals(((ScriptStep.Send) prepared.script().steps().get(i)).data(), transport.sent.get(i)));
        }
    }

    @Test
    void thePlanIsBuiltOnceForRepeatedReplays() throws IOException {
        Record record = recordOf(SampleIcdMessages.status(1, 80_000, 0), 10);
        categoryWithProfile(record.categoryId());

        ScriptSource.Prepared first = test.catalog.scriptSource().prepare(record.id());
        ScriptSource.Prepared again = test.catalog.scriptSource().prepare(record.id());

        assertSame(first.timeFix(), again.timeFix());
    }

    // ------------------------------------------------------------------ helpers

    private Category categoryWithProfile(String categoryId) {
        Category category = test.catalog.icds().setCategoryIcd(categoryId, icd.id());
        test.catalog.timeFix().save(categoryId, new TimeFixService.SaveRequest(List.of(
                new MessageTimeFix(1, new TimeFieldRef("header.sendTime", ETimeKind.LTIME),
                        List.of(new TimeFieldRef("timestampMs", ETimeKind.REGULAR_MILLIS))),
                new MessageTimeFix(2, new TimeFieldRef("gpsTime", ETimeKind.GPS),
                        List.of(new TimeFieldRef("targets[*].seenGps", ETimeKind.GPS))))));
        return category;
    }

    /** A record of a TCP connection carrying {@code stream}, cut into segments at {@code cuts}, 1 ms apart. */
    private Record recordOf(byte[] stream, int... cuts) throws IOException {
        List<Frame> frames = new ArrayList<>();
        frames.add(segment(0, 999, Frames.SYN, new byte[0]));
        int from = 0;
        int[] ends = Arrays.copyOf(cuts, cuts.length + 1);
        ends[cuts.length] = stream.length;

        for (int end : ends) {
            frames.add(segment(frames.size(), 1000 + from, Frames.ACK | Frames.PSH, Arrays.copyOfRange(stream, from, end)));
            from = end;
        }
        Path pcap = CaptureFiles.writePcap(dir.resolve("capture.pcap"), CaptureFiles.LINKTYPE_ETHERNET, frames);
        Path temp = test.uploads.newTempPath();
        Files.copy(pcap, temp);
        UploadService.Upload upload = test.uploads.register(temp, "capture.pcap", "test");

        Category category = test.catalog.taxonomy().createCategory("Radar " + System.nanoTime(), EConnectionMode.TCP_SERVER);
        long startUs = TimeUnit.NANOSECONDS.toMicros(CAPTURE_NANOS);
        long endUs = startUs + frames.size() * 1000L;
        return test.catalog.records().create(new RecordService.CreateRequest(upload.id(), null, "record", "", category.id(),
                List.of(), List.of(SERVICE_PORT), null, startUs, endUs, "test", null));
    }

    private static Frame segment(int index, long seq, int flags, byte[] data) {
        byte[] tcp = Frames.tcp(40_000, SERVICE_PORT, seq, 0, flags, data);
        return new Frame(CAPTURE_NANOS + index * MS, Frames.ethernet(Frames.ipv4("10.0.0.1", "10.0.0.2", Frames.PROTO_TCP, tcp)));
    }

    /** Plays the item on this thread through the real player. */
    private static RecordingTransport play(ScriptSource.Prepared prepared, TimeFixer timeFixer) {
        RecordingTransport transport = new RecordingTransport();
        Control control = new Control();
        control.bind(Thread.currentThread());
        ItemProgress progress = new ItemProgress(prepared.script().durationNanos(), prepared.script().messageCount());
        ItemPlayer.play(prepared.script(), transport, control, progress, state -> { }, (level, code, params) -> { }, timeFixer);
        return transport;
    }

    private List<DecodedMessage> decode(List<byte[]> sent, ReplayScript original) {
        List<ScriptStep> steps = new ArrayList<>();
        for (int i = 0; i < sent.size(); i++) steps.add(new ScriptStep.Send(original.steps().get(i).offsetNanos(), sent.get(i)));
        ReplayScript script = new ReplayScript(original.mode(), steps, steps.size(), 0, original.durationNanos());
        return ScriptDecoder.decodeAll(script, CAPTURE_NANOS, icd);
    }

    /** Every byte that differs from the recording lies inside a planned time field. */
    private static void assertOnlyPlannedBytesChanged(ReplayScript script, List<byte[]> sent, PatchPlan plan) {
        Set<String> allowed = new HashSet<>();

        for (int step = 0; step < script.steps().size(); step++) {
            for (MessagePatch message : plan.startingAt(step)) {
                for (FieldPatch field : message.fields()) {
                    field.spans().forEach(span -> {
                        for (int i = 0; i < span.length(); i++) allowed.add(span.step() + ":" + (span.offset() + i));
                    });
                }
            }
        }
        int changed = 0;

        for (int step = 0; step < sent.size(); step++) {
            byte[] recorded = ((ScriptStep.Send) script.steps().get(step)).data();

            for (int i = 0; i < recorded.length; i++) {
                if (recorded[i] == sent.get(step)[i]) continue;
                changed++;
                assertTrue(allowed.contains(step + ":" + i), "byte " + i + " of packet " + step + " is not a time field");
            }
        }
        assertTrue(changed > 0);
    }

    private static long ticks(long nanosOfDay) {
        return nanosOfDay / 25_000;
    }
}
