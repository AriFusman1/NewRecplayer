package com.pcapplayer.icd.profile;

import com.pcapplayer.icd.timefix.ETimeKind;
import com.pcapplayer.icd.tree.IcdTree;
import com.pcapplayer.icd.tree.IcdTreeBuilder;
import com.pcapplayer.pcapProcessing.diagnostics.Warning;
import com.pcapplayer.testSupport.SampleIcdMessages;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeFixProfileValidatorTest {

    private static final long NOW = Instant.parse("2026-11-24T10:00:00Z").getEpochSecond() * 1_000_000_000L;

    private final IcdTree tree = IcdTreeBuilder.of(SampleIcdMessages.icd());

    @Test
    void aGoodProfileHasNoErrorsOrWarnings() {
        ProfileCheck check = check(new MessageTimeFix(2, ref("gpsTime", ETimeKind.GPS), List.of(
                ref("epochSeconds", ETimeKind.REGULAR_SECONDS),
                ref("ltimeExact", ETimeKind.LTIME),
                ref("targets[*].seenGps", ETimeKind.GPS))));

        assertEquals(List.of(), check.errors());
        assertEquals(List.of(), check.warnings());
    }

    @Test
    void fieldsNeedABase() {
        assertEquals(List.of("TIMEFIX_NO_BASE"), codes(check(new MessageTimeFix(1, null, List.of(ref("timestampMs", ETimeKind.REGULAR_MILLIS)))).errors()));
        assertEquals(List.of(), check(new MessageTimeFix(1, null, List.of())).errors(), "an empty entry is fine");
    }

    @Test
    void pathsMustExistAndHoldNumbers() {
        ProfileCheck check = check(new MessageTimeFix(1, ref("header.sendTime", ETimeKind.LTIME), List.of(
                ref("nope", ETimeKind.LTIME),
                ref("unitName", ETimeKind.LTIME),     // string
                ref("status", ETimeKind.LTIME),       // enum
                ref("healthy", ETimeKind.LTIME),      // BOOL
                ref("flags", ETimeKind.LTIME),        // the bitfield itself, not a bit
                ref("timestampMs", null))));

        assertEquals(List.of("TIMEFIX_UNKNOWN_FIELD", "TIMEFIX_NOT_NUMERIC", "TIMEFIX_NOT_NUMERIC", "TIMEFIX_NOT_NUMERIC",
                "TIMEFIX_NOT_NUMERIC", "TIMEFIX_KIND_REQUIRED"), codes(check.errors()));
    }

    @Test
    void otherErrors() {
        ProfileCheck check = check(
                new MessageTimeFix(99, ref("x", ETimeKind.GPS), List.of()),
                new MessageTimeFix(2, ref("targets[*].seenGps", ETimeKind.GPS), List.of(ref("targets[*].seenGps", ETimeKind.GPS))),
                new MessageTimeFix(2, ref("gpsTime", ETimeKind.GPS), List.of()));

        assertEquals(List.of("TIMEFIX_UNKNOWN_MESSAGE", "TIMEFIX_BASE_IN_ARRAY", "TIMEFIX_DUPLICATE_FIELD", "TIMEFIX_DUPLICATE_MESSAGE"),
                codes(check.errors()));
    }

    @Test
    void warnsWhenTheTypeIsTooSmallOrTooImprecise() {
        ProfileCheck check = check(new MessageTimeFix(2, ref("gpsTime", ETimeKind.GPS), List.of(
                ref("epochSeconds", ETimeKind.REGULAR_MILLIS),   // UINT32 cannot hold epoch ms
                ref("targets[*].pos.alt", ETimeKind.LTIME),      // INT16 < 86 399
                ref("targets[*].id", ETimeKind.GPS),             // UINT16 < 3 455 999 999
                ref("targets[*].pos.lat", ETimeKind.REGULAR_SECONDS), // FLOAT32: 128 s steps
                ref("targets[*].pos.lon", ETimeKind.LTIME),      // FLOAT32 is fine for LTime
                ref("temperature", ETimeKind.REGULAR_SECONDS))));

        assertEquals(List.of(), check.errors());
        assertEquals(List.of("TIMEFIX_TYPE_TOO_SMALL", "TIMEFIX_TYPE_TOO_SMALL", "TIMEFIX_TYPE_TOO_SMALL", "TIMEFIX_FLOAT_PRECISION",
                "TIMEFIX_TYPE_TOO_SMALL"), codes(check.warnings()));
        assertEquals("REGULAR_MILLIS", check.warnings().get(0).params().get("kind"));
        assertEquals("UINT32", check.warnings().get(0).params().get("type"));
    }

    @Test
    void aBitIsCheckedAgainstItsWidth() {
        ProfileCheck check = check(new MessageTimeFix(1, ref("header.sendTime", ETimeKind.LTIME), List.of(ref("flags.spare", ETimeKind.LTIME))));

        assertEquals("4 bits", check.warnings().get(0).params().get("type"));
    }

    private ProfileCheck check(MessageTimeFix... messages) {
        return TimeFixProfileValidator.check(List.of(messages), tree, NOW);
    }

    private static TimeFieldRef ref(String path, ETimeKind kind) {
        return new TimeFieldRef(path, kind);
    }

    private static List<String> codes(List<Warning> warnings) {
        assertTrue(warnings.stream().allMatch(warning -> warning.code().startsWith("TIMEFIX_")));
        return warnings.stream().map(Warning::code).toList();
    }
}
