package com.pcapplayer.icd.timefix;

import com.pcapplayer.icd.decode.DecodedMessage;
import com.pcapplayer.icd.decode.ScriptDecoder;
import com.pcapplayer.icd.model.IcdDefinition;
import com.pcapplayer.icd.profile.MessageTimeFix;
import com.pcapplayer.icd.profile.TimeFieldRef;
import com.pcapplayer.icd.profile.TimeFixProfile;
import com.pcapplayer.pcapProcessing.script.ReplayScript;
import com.pcapplayer.pcapProcessing.script.ScriptStep;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plans built from decoded messages, applied with a chosen "now", checked by decoding the patched bytes.
 * Expected values are computed with java.time, independently of {@link TimeCodec}.
 */
class TimeFixPlannerTest {

    private static final long SECOND = 1_000_000_000L;
    private static final long MS = 1_000_000L;
    private static final long DAY = 86_400 * SECOND;
    private static final Map<String, ETimeKind> KINDS = Map.of("s", ETimeKind.REGULAR_SECONDS, "ms", ETimeKind.REGULAR_MILLIS,
            "lt", ETimeKind.LTIME, "gps", ETimeKind.GPS);

    /** Original times: s at T0, ms at T0 + 250 ms, lt at T0 + 500 ms, gps at T0 + 750 ms. */
    private static final long T0 = nanos("2023-11-14T22:13:20Z");
    private static final Map<String, Long> ORIGINAL = Map.of("s", T0, "ms", T0 + 250 * MS, "lt", T0 + 500 * MS, "gps", T0 + 750 * MS);
    private static final long NOW = nanos("2026-11-24T10:00:00.123456Z");

    private final IcdDefinition icd = TimesIcd.icd();

    @Test
    void everyBaseKindWithEveryFieldKind() {
        byte[] message = TimesIcd.message(T0 / SECOND, (T0 + 250 * MS) / MS, 80_000.5, 80_000_750_000_000L / 25_000);

        for (String base : KINDS.keySet()) {
            List<TimeFieldRef> fields = KINDS.keySet().stream().filter(path -> !path.equals(base))
                    .map(path -> new TimeFieldRef(path, KINDS.get(path))).toList();
            TimeFixProfile profile = profile(new MessageTimeFix(7, new TimeFieldRef(base, KINDS.get(base)), fields));
            DecodedMessage fixed = applyAndDecode(TimesIcd.script(message), T0, profile, NOW).get(0);

            for (String path : KINDS.keySet()) {
                long expectedInstant = NOW + ORIGINAL.get(path) - ORIGINAL.get(base);
                assertEquals(expected(path, expectedInstant), fixed.fields().get(path).value(), "base " + base + ", field " + path);
            }
        }
    }

    @Test
    void aRecordCrossingMidnight() {
        // captured at 23:59:59.9; base LTime 23:59:59.9, a GPS field at 00:00:00.1 of the next day
        long capture = nanos("2023-11-14T23:59:59.900Z");
        byte[] message = TimesIcd.message(0, 0, 86_399.9, 4_000);
        TimeFixProfile profile = profile(new MessageTimeFix(7, new TimeFieldRef("lt", ETimeKind.LTIME),
                List.of(new TimeFieldRef("gps", ETimeKind.GPS))));

        DecodedMessage fixed = applyAndDecode(TimesIcd.script(message), capture, profile, NOW).get(0);

        assertEquals(expected("lt", NOW), fixed.fields().get("lt").value());
        assertEquals(expected("gps", NOW + 200 * MS), fixed.fields().get("gps").value(), "+0.2 s, not −24 h");
    }

    @Test
    void nowJustAfterMidnight() {
        // the GPS field is 0.2 s before the base; sent 50 ms after midnight it lands on the previous day
        byte[] message = TimesIcd.message(0, 0, 80_000.5, 80_000_300_000_000L / 25_000);
        TimeFixProfile profile = profile(new MessageTimeFix(7, new TimeFieldRef("lt", ETimeKind.LTIME),
                List.of(new TimeFieldRef("gps", ETimeKind.GPS))));
        long now = nanos("2026-11-25T00:00:00.050Z");

        DecodedMessage fixed = applyAndDecode(TimesIcd.script(message), T0, profile, now).get(0);

        assertEquals(0.05, (Double) fixed.fields().get("lt").value(), 1e-9);
        assertEquals((DAY - 150 * MS) / 25_000, fixed.fields().get("gps").value(), "23:59:59.85 in ticks");
    }

    @Test
    void valuesThatDoNotFitAreClampedAndCounted() {
        byte[] message = TimesIcd.message(0, 0, 0, 0);
        TimeFixProfile profile = profile(new MessageTimeFix(7, new TimeFieldRef("lt", ETimeKind.LTIME),
                List.of(new TimeFieldRef("gps", ETimeKind.REGULAR_MILLIS))));  // epoch ms in a UINT32: too big
        TimeFixStats stats = new TimeFixStats();

        DecodedMessage fixed = applyAndDecode(TimesIcd.script(message), T0, profile, NOW, icd, stats).get(0);

        assertEquals(4_294_967_295L, fixed.fields().get("gps").value());
        assertEquals(1, stats.clampedValues());
    }

    @Test
    void messagesWithoutAProfileEntryAreNotPlanned() {
        TimeFixProfile profile = profile(new MessageTimeFix(99, new TimeFieldRef("s", ETimeKind.REGULAR_SECONDS), List.of()));
        TimeFixPlanner planner = new TimeFixPlanner(profile, PostPatchHook.NONE, new TimeFixStats());

        ScriptDecoder.decode(TimesIcd.script(TimesIcd.message(1, 1, 1, 1)), T0, icd, planner);

        assertTrue(planner.plan().empty());
    }

    // ------------------------------------------------------------------ helpers

    /** The value a field of the Times ICD must hold for {@code instant}, computed with java.time. */
    private static Object expected(String path, long instant) {
        Instant time = Instant.ofEpochSecond(Math.floorDiv(instant, SECOND), Math.floorMod(instant, SECOND));
        long nanosOfDay = time.atZone(ZoneOffset.UTC).toLocalTime().toNanoOfDay();

        return switch (path) {
            case "s" -> time.getEpochSecond();
            case "ms" -> time.toEpochMilli();
            case "lt" -> nanosOfDay / 1e9;
            default -> nanosOfDay / 25_000;
        };
    }

    private List<DecodedMessage> applyAndDecode(ReplayScript script, long captureStart, TimeFixProfile profile, long now) {
        return applyAndDecode(script, captureStart, profile, now, icd, new TimeFixStats());
    }

    /** Plans, applies every patch with {@code now} into copies, and decodes the copies. */
    static List<DecodedMessage> applyAndDecode(ReplayScript script, long captureStart, TimeFixProfile profile, long now,
                                               IcdDefinition icd, TimeFixStats stats) {
        PatchPlan plan = plan(script, captureStart, profile, icd, stats);
        StepCopies copies = new StepCopies(script.steps());

        for (int step = 0; step < script.steps().size(); step++) {
            for (MessagePatch patch : plan.startingAt(step)) patch.apply(now, copies, stats);
        }
        return ScriptDecoder.decodeAll(patchedScript(script, copies), captureStart, icd);
    }

    static PatchPlan plan(ReplayScript script, long captureStart, TimeFixProfile profile, IcdDefinition icd, TimeFixStats stats) {
        TimeFixPlanner planner = new TimeFixPlanner(profile, PostPatchHook.NONE, stats);
        ScriptDecoder.decode(script, captureStart, icd, planner);
        return planner.plan();
    }

    /** The script with every patched step replaced by its copy. */
    static ReplayScript patchedScript(ReplayScript script, StepCopies copies) {
        List<ScriptStep> steps = new ArrayList<>();

        for (int i = 0; i < script.steps().size(); i++) {
            ScriptStep.Send send = (ScriptStep.Send) script.steps().get(i);
            byte[] copy = copies.take(i);
            steps.add(new ScriptStep.Send(send.offsetNanos(), copy != null ? copy : send.data(), send.flowId()));
        }
        return new ReplayScript(script.mode(), steps, script.messageCount(), script.totalBytes(), script.durationNanos(), script.flows());
    }

    static TimeFixProfile profile(MessageTimeFix... messages) {
        return new TimeFixProfile("category", "times", 0, List.of(messages));
    }

    static long nanos(String isoInstant) {
        Instant instant = Instant.parse(isoInstant);
        return instant.getEpochSecond() * SECOND + instant.getNano();
    }
}
