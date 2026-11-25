package com.pcapplayer.icd.timefix;

import com.pcapplayer.icd.decode.ByteSpan;
import com.pcapplayer.icd.decode.DecodedMessage;
import com.pcapplayer.icd.decode.ScriptDecoder;
import com.pcapplayer.icd.model.IcdDefinition;
import com.pcapplayer.icd.profile.MessageTimeFix;
import com.pcapplayer.icd.profile.TimeFieldRef;
import com.pcapplayer.icd.profile.TimeFixProfile;
import com.pcapplayer.pcapProcessing.script.EConnectionMode;
import com.pcapplayer.pcapProcessing.script.ReplayScript;
import com.pcapplayer.pcapProcessing.script.ScriptStep;
import com.pcapplayer.testSupport.SampleIcdMessages;
import com.pcapplayer.testSupport.SampleIcdMessages.TargetValues;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.pcapplayer.icd.timefix.TimeFixPlannerTest.nanos;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Patching messages of the sample ICD split across packets, with arrays, mixed byte orders and kinds. */
class SampleIcdPatchTest {

    private static final long SECOND = 1_000_000_000L;
    private static final long MS = 1_000_000L;

    /** 2023-11-14T22:13:20Z; the header's LTime sendTime is 80 000. */
    private static final long CAPTURE = nanos("2023-11-14T22:13:20Z");
    private static final long NOW = nanos("2026-11-24T10:00:00.250Z");
    private static final long NOW_OF_DAY = 36_000 * SECOND + 250 * MS;

    private final IcdDefinition icd = SampleIcdMessages.icd();

    private final TimeFixProfile profile = new TimeFixProfile("category", "sample-v1", 0, List.of(
            new MessageTimeFix(1, new TimeFieldRef("header.sendTime", ETimeKind.LTIME),
                    List.of(new TimeFieldRef("timestampMs", ETimeKind.REGULAR_MILLIS))),
            new MessageTimeFix(2, new TimeFieldRef("gpsTime", ETimeKind.GPS), List.of(
                    new TimeFieldRef("epochSeconds", ETimeKind.REGULAR_SECONDS),
                    new TimeFieldRef("ltimeExact", ETimeKind.LTIME),
                    new TimeFieldRef("targets[*].seenGps", ETimeKind.GPS)))));

    @Test
    void patchesAcrossPacketsAndLeavesEveryOtherByteAlone() {
        // Status: sent at LTime 80 000, timestampMs 1.5 s later. Track: gpsTime at 80 000 s; the other
        // fields 1 s, 2.5 s, −0.5 s and +3 s from it.
        byte[] status = SampleIcdMessages.status(1, 80_000, (CAPTURE + 1_500 * MS) / MS);
        byte[] track = SampleIcdMessages.track(2, 80_000, ticks(80_000 * SECOND), CAPTURE / SECOND + 1, 80_002.5,
                new TargetValues(1, 1f, 2f, (short) 3, ticks(79_999_500 * MS), (byte) 1),
                new TargetValues(2, 4f, 5f, (short) 6, ticks(80_003 * SECOND), (byte) 2));
        byte[] stream = SampleIcdMessages.concat(status, track);
        // packets cut inside timestampMs (bytes 24..31) and inside targets[0].seenGps
        int[] cuts = {28, SampleIcdMessages.STATUS_SIZE + 12 + 21 + 14};
        ReplayScript script = split(stream, cuts);

        TimeFixStats stats = new TimeFixStats();
        PatchPlan plan = TimeFixPlannerTest.plan(script, CAPTURE, profile, icd, stats);
        StepCopies copies = new StepCopies(script.steps());

        for (int step = 0; step < script.steps().size(); step++) {
            for (MessagePatch patch : plan.startingAt(step)) patch.apply(NOW, copies, stats);
        }
        ReplayScript patched = TimeFixPlannerTest.patchedScript(script, copies);
        List<DecodedMessage> messages = ScriptDecoder.decodeAll(patched, CAPTURE, icd);

        assertEquals(2, plan.messageCount());
        DecodedMessage fixedStatus = messages.get(0);
        assertEquals(36_000L, fixedStatus.fields().get("header.sendTime").value(), "LTime of now");
        assertEquals((NOW + 1_500 * MS) / MS, fixedStatus.fields().get("timestampMs").value(), "split across packets 0 and 1");

        DecodedMessage fixedTrack = messages.get(1);
        assertEquals(ticks(NOW_OF_DAY), fixedTrack.fields().get("gpsTime").value());
        assertEquals((NOW + SECOND) / SECOND, fixedTrack.fields().get("epochSeconds").value());
        assertEquals((NOW_OF_DAY + 2_500 * MS) / 1e9, fixedTrack.fields().get("ltimeExact").value());
        assertEquals(ticks(NOW_OF_DAY - 500 * MS), fixedTrack.fields().get("targets[0].seenGps").value(), "split across packets 1 and 2");
        assertEquals(ticks(NOW_OF_DAY + 3 * SECOND), fixedTrack.fields().get("targets[1].seenGps").value());

        assertOnlyTimeBytesChanged(script, patched, plan);
        assertEquals(0, stats.clampedValues());
        assertEquals(2, stats.patchedMessages());
    }

    @Test
    void theCachedScriptIsNeverWritten() {
        byte[] status = SampleIcdMessages.status(1, 80_000, CAPTURE / MS);
        byte[] original = status.clone();
        ReplayScript script = split(status);

        PatchPlan plan = TimeFixPlannerTest.plan(script, CAPTURE, profile, icd, new TimeFixStats());
        StepCopies copies = new StepCopies(script.steps());
        plan.startingAt(0).forEach(patch -> patch.apply(NOW, copies, new TimeFixStats()));

        assertTrue(Arrays.equals(original, ((ScriptStep.Send) script.steps().get(0)).data()));
        assertTrue(!Arrays.equals(original, copies.bytes(0)));
    }

    /** Every byte that differs between the scripts lies inside a planned time field. */
    private static void assertOnlyTimeBytesChanged(ReplayScript original, ReplayScript patched, PatchPlan plan) {
        Set<String> allowed = new HashSet<>();

        for (int step = 0; step < original.steps().size(); step++) {
            for (MessagePatch message : plan.startingAt(step)) {
                for (FieldPatch field : message.fields()) {
                    for (ByteSpan span : field.spans()) {
                        for (int i = 0; i < span.length(); i++) allowed.add(span.step() + ":" + (span.offset() + i));
                    }
                }
            }
        }
        int changed = 0;

        for (int step = 0; step < original.steps().size(); step++) {
            byte[] before = ((ScriptStep.Send) original.steps().get(step)).data();
            byte[] after = ((ScriptStep.Send) patched.steps().get(step)).data();
            assertEquals(before.length, after.length, "packet sizes never change");

            for (int i = 0; i < before.length; i++) {
                if (before[i] == after[i]) continue;
                changed++;
                assertTrue(allowed.contains(step + ":" + i), "byte " + i + " of packet " + step + " is not a time field");
            }
        }
        assertTrue(changed > 0);
    }

    private static ReplayScript split(byte[] stream, int... cuts) {
        List<ScriptStep> steps = new ArrayList<>();
        int from = 0;

        for (int cut : cuts) {
            steps.add(new ScriptStep.Send(steps.size() * MS, Arrays.copyOfRange(stream, from, cut)));
            from = cut;
        }
        steps.add(new ScriptStep.Send(steps.size() * MS, Arrays.copyOfRange(stream, from, stream.length)));
        return new ReplayScript(EConnectionMode.TCP_SERVER, steps, steps.size(), stream.length, steps.size() * MS);
    }

    private static long ticks(long nanosOfDay) {
        return nanosOfDay / 25_000;
    }
}
