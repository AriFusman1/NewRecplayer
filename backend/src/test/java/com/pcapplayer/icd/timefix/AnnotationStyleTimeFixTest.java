package com.pcapplayer.icd.timefix;

import com.pcapplayer.icd.decode.DecodedMessage;
import com.pcapplayer.icd.loading.JsonIcdLoader;
import com.pcapplayer.icd.model.IcdDefinition;
import com.pcapplayer.icd.profile.MessageTimeFix;
import com.pcapplayer.icd.profile.ProfileCheck;
import com.pcapplayer.icd.profile.TimeFieldRef;
import com.pcapplayer.icd.profile.TimeFixProfile;
import com.pcapplayer.icd.profile.TimeFixProfileValidator;
import com.pcapplayer.icd.tree.IcdTreeBuilder;
import com.pcapplayer.pcapProcessing.script.EConnectionMode;
import com.pcapplayer.pcapProcessing.script.ReplayScript;
import com.pcapplayer.pcapProcessing.script.ScriptStep;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.pcapplayer.icd.timefix.TimeFixPlannerTest.nanos;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Time fixing works the same with an ICD written in the annotation style: a time in {@code @Bits} of a
 * 24-bit chunk, an {@code @Unsigned long}, and a field of every element of an array counted in bytes.
 */
class AnnotationStyleTimeFixTest {

    private static final long SECOND = 1_000_000_000L;
    private static final long MS = 1_000_000L;
    /** 22:13:20 UTC = second 80 000 of the day. */
    private static final long CAPTURE = nanos("2023-11-14T22:13:20Z");
    private static final long NOW = nanos("2026-11-24T10:00:00.250Z");
    private static final long NOW_OF_DAY_SECONDS = 36_000;

    private static final String ICD = """
            {
              "format": "annotations", "id": "gw-times", "name": "GW times",
              "header": { "class": "Header", "opcodeField": "msgType", "lengthField": "msgLength" },
              "messages": [ { "opcode": 5, "class": "Report" } ],
              "classes": {
                "Header": { "fields": [
                  { "ordinal": 0, "name": "msgType", "type": "int", "unsigned": true },
                  { "ordinal": 1, "name": "msgLength", "type": "int", "unsigned": true },
                  { "ordinal": 2, "name": "sendTime", "type": "long", "unsigned": true }
                ] },
                "Report": { "fields": [
                  { "ordinal": 0, "name": "ltimeSec", "type": "int", "unsigned": true, "bits": { "value": 17, "bitfield": "BITS24" } },
                  { "ordinal": 1, "name": "spare", "type": "int", "bits": { "value": 7, "bitfield": "BITS24" } },
                  { "ordinal": 2, "name": "epochMs", "type": "long" },
                  { "ordinal": 3, "name": "count", "type": "int", "lengthEncoder": { "isLengthInBytes": true } },
                  { "ordinal": 4, "name": "plots", "type": "Plot[]", "lengthEncoded": { "encodingField": "count" } },
                  { "ordinal": 5, "name": "label", "type": "String", "size": 4 }
                ] },
                "Plot": { "fields": [
                  { "ordinal": 0, "name": "seenSec", "type": "long", "unsigned": true },
                  { "ordinal": 1, "name": "id", "type": "short" }
                ] }
              }
            }""";

    private static final List<MessageTimeFix> FIXES = List.of(new MessageTimeFix(5,
            new TimeFieldRef("header.sendTime", ETimeKind.LTIME), List.of(
            new TimeFieldRef("ltimeSec", ETimeKind.LTIME),
            new TimeFieldRef("epochMs", ETimeKind.REGULAR_MILLIS),
            new TimeFieldRef("plots[*].seenSec", ETimeKind.REGULAR_SECONDS))));

    /** Header 8 bytes (UINT16, UINT16, UINT32), then 3 + 8 + 4 + 2 × 6 + 4 bytes. */
    private static byte[] report() {
        ByteBuffer message = ByteBuffer.allocate(8 + 31);
        message.putShort((short) 5).putShort((short) 39).putInt(80_000);         // sent at second 80 000
        int chunk = 80_001 << 7 | 85;                                             // ltimeSec 1 s later, spare 85
        message.put((byte) (chunk >> 16)).put((byte) (chunk >> 8)).put((byte) chunk);
        message.putLong((CAPTURE + 2 * SECOND) / MS);                            // 2 s later
        message.putInt(12);                                                       // 12 bytes = 2 plots
        message.putInt((int) (CAPTURE / SECOND - 1)).putShort((short) 7);         // 1 s earlier
        message.putInt((int) (CAPTURE / SECOND + 3)).putShort((short) 9);         // 3 s later
        message.put("ABCD".getBytes(StandardCharsets.US_ASCII));
        return message.array();
    }

    /** Two packets, cut inside the 24-bit chunk. */
    private static ReplayScript script(byte[] message) {
        List<ScriptStep> steps = List.of(new ScriptStep.Send(0, Arrays.copyOfRange(message, 0, 10)),
                new ScriptStep.Send(MS, Arrays.copyOfRange(message, 10, message.length)));
        return new ReplayScript(EConnectionMode.TCP_SERVER, steps, 2, message.length, 2 * MS);
    }

    @Test
    void theProfileIsValidForTheAnnotationStyleIcd() throws Exception {
        IcdDefinition icd = JsonIcdLoader.load(ICD.getBytes(StandardCharsets.UTF_8));

        ProfileCheck check = TimeFixProfileValidator.check(FIXES, IcdTreeBuilder.of(icd), NOW);

        assertEquals(List.of(), check.errors());
    }

    @Test
    void timesAreRewrittenToNowAndEveryOtherFieldIsLeftAlone() throws Exception {
        IcdDefinition icd = JsonIcdLoader.load(ICD.getBytes(StandardCharsets.UTF_8));
        TimeFixProfile profile = new TimeFixProfile("category", "gw-times", 0, FIXES);
        TimeFixStats stats = new TimeFixStats();

        List<DecodedMessage> messages = TimeFixPlannerTest.applyAndDecode(script(report()), CAPTURE, profile, NOW, icd, stats);

        Map<String, ?> fields = messages.get(0).fields().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().value()));
        assertEquals(NOW_OF_DAY_SECONDS, fields.get("header.sendTime"));
        assertEquals(NOW_OF_DAY_SECONDS + 1, fields.get("ltimeSec"), "a time in @Bits of a 24-bit chunk");
        assertEquals(85L, fields.get("spare"), "the other bits of the chunk are untouched");
        assertEquals((NOW + 2 * SECOND) / MS, fields.get("epochMs"));
        assertEquals((NOW - SECOND) / SECOND, fields.get("plots[0].seenSec"));
        assertEquals((NOW + 3 * SECOND) / SECOND, fields.get("plots[1].seenSec"));
        assertEquals(12L, fields.get("count"));
        assertEquals(9L, fields.get("plots[1].id"));
        assertEquals("ABCD", fields.get("label"));
        assertEquals(0, stats.clampedValues());
        assertEquals(1, stats.patchedMessages());
    }
}
