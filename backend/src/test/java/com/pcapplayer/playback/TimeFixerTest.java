package com.pcapplayer.playback;

import com.pcapplayer.icd.decode.DecodedMessage;
import com.pcapplayer.icd.decode.ScriptDecoder;
import com.pcapplayer.icd.model.IcdDefinition;
import com.pcapplayer.icd.profile.MessageTimeFix;
import com.pcapplayer.icd.profile.TimeFieldRef;
import com.pcapplayer.icd.profile.TimeFixProfile;
import com.pcapplayer.icd.timefix.ETimeKind;
import com.pcapplayer.icd.timefix.PatchPlan;
import com.pcapplayer.icd.timefix.PostPatchHook;
import com.pcapplayer.icd.timefix.TimeFixPlanner;
import com.pcapplayer.icd.timefix.TimeFixStats;
import com.pcapplayer.pcapProcessing.script.EConnectionMode;
import com.pcapplayer.pcapProcessing.script.ReplayScript;
import com.pcapplayer.pcapProcessing.script.ScriptStep;
import com.pcapplayer.testSupport.SampleIcdMessages;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.pcapplayer.testSupport.SampleIcdMessages.concat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Send-time patching: one clock read per message, at its first packet; copies only where needed; seeks. */
class TimeFixerTest {

    private static final Instant CAPTURE = Instant.parse("2023-11-14T22:13:20Z"); // LTime 80 000
    private static final long CAPTURE_NANOS = CAPTURE.getEpochSecond() * 1_000_000_000L;
    private static final long CAPTURE_MS = CAPTURE.toEpochMilli();
    private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");     // LTime 36 000

    private final IcdDefinition icd = SampleIcdMessages.icd();
    private final TimeFixProfile profile = new TimeFixProfile("category", "sample-v1", 0, List.of(
            new MessageTimeFix(1, new TimeFieldRef("header.sendTime", ETimeKind.LTIME),
                    List.of(new TimeFieldRef("timestampMs", ETimeKind.REGULAR_MILLIS)))));

    // Status 0 over packets 0-2 (timestampMs split between 1 and 2), Status 1 in packet 2,
    // a Track (not in the profile) in packet 3, Status 2 over packets 4-5. Each timestampMs is 250 ms after its sendTime.
    private final byte[] status0 = SampleIcdMessages.status(0, 80_000, CAPTURE_MS + 250);
    private final byte[] status1 = SampleIcdMessages.status(1, 80_001, CAPTURE_MS + 1_250);
    private final byte[] status2 = SampleIcdMessages.status(2, 80_002, CAPTURE_MS + 2_250);
    private final byte[] track = SampleIcdMessages.track(3, 0, 0, 0, 0);
    private final ReplayScript script = script(
            Arrays.copyOfRange(status0, 0, 10), Arrays.copyOfRange(status0, 10, 28),
            concat(Arrays.copyOfRange(status0, 28, 37), status1), track,
            Arrays.copyOfRange(status2, 0, 20), Arrays.copyOfRange(status2, 20, 37));

    @Test
    void eachMessageGetsTheTimeItsFirstPacketIsSent() {
        TickingClock clock = new TickingClock(NOW, Duration.ofSeconds(1));
        TimeFixer fixer = new TimeFixer(plan(), script.steps(), clock);
        List<byte[]> sent = sendAll(fixer, 0);

        List<DecodedMessage> messages = decode(sent);
        assertEquals(3, clock.reads(), "one read per patched message");
        assertStatus(messages.get(0), clock.read(0));
        assertStatus(messages.get(1), clock.read(1));
        assertStatus(messages.get(3), clock.read(2));
        assertSame(data(3), sent.get(3), "a packet without patches is sent as is, not copied");
        assertArrayEquals(track, sent.get(3));
    }

    @Test
    void aSeekIntoAMessagePatchesItAtItsFirstRemainingPacket() {
        TickingClock clock = new TickingClock(NOW, Duration.ofSeconds(1));
        TimeFixer fixer = new TimeFixer(plan(), script.steps(), clock);
        fixer.bytesToSend(0, data(0)); // Status 0 patched with read 0 ...

        fixer.seekTo(1);                // ... then a jump back to packet 1, inside Status 0
        List<byte[]> sent = new ArrayList<>(List.of(data(0)));
        sent.addAll(sendAll(fixer, 1));

        DecodedMessage status = decode(sent).get(0);
        assertEquals((clock.read(1).toEpochMilli() + 250), status.fields().get("timestampMs").value(),
                "patched again with the time packet 1 was sent");
        assertEquals(4, clock.reads());
    }

    @Test
    void theScriptItselfIsNeverWritten() {
        byte[] before = data(1).clone();

        sendAll(new TimeFixer(plan(), script.steps(), new TickingClock(NOW, Duration.ofSeconds(1))), 0);

        assertArrayEquals(before, data(1));
    }

    private void assertStatus(DecodedMessage status, Instant sentAt) {
        long secondsOfDay = sentAt.getEpochSecond() % 86_400;
        assertEquals(secondsOfDay, status.fields().get("header.sendTime").value());
        assertEquals(sentAt.toEpochMilli() + 250, status.fields().get("timestampMs").value());
    }

    private List<byte[]> sendAll(TimeFixer fixer, int from) {
        List<byte[]> sent = new ArrayList<>();
        for (int step = from; step < script.steps().size(); step++) sent.add(fixer.bytesToSend(step, data(step)));
        return sent;
    }

    private PatchPlan plan() {
        TimeFixPlanner planner = new TimeFixPlanner(profile, PostPatchHook.NONE, new TimeFixStats());
        ScriptDecoder.decode(script, CAPTURE_NANOS, icd, planner);
        return planner.plan();
    }

    private List<DecodedMessage> decode(List<byte[]> packets) {
        return ScriptDecoder.decodeAll(script(packets.toArray(byte[][]::new)), CAPTURE_NANOS, icd);
    }

    private byte[] data(int step) {
        return ((ScriptStep.Send) script.steps().get(step)).data();
    }

    private static ReplayScript script(byte[]... packets) {
        List<ScriptStep> steps = new ArrayList<>();
        for (byte[] packet : packets) steps.add(new ScriptStep.Send(steps.size() * 1_000_000L, packet));
        return new ReplayScript(EConnectionMode.TCP_SERVER, steps, steps.size(), 0, steps.size() * 1_000_000L);
    }
}
