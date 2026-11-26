package com.pcapplayer.icd.loading.annotationJson;

import com.pcapplayer.icd.decode.FieldValue;
import com.pcapplayer.icd.decode.MessageDecoder;
import com.pcapplayer.icd.loading.IcdLoadException;
import com.pcapplayer.icd.loading.IcdProblem;
import com.pcapplayer.icd.loading.JsonIcdLoader;
import com.pcapplayer.icd.model.EFieldType;
import com.pcapplayer.icd.model.EValidity;
import com.pcapplayer.icd.model.IcdDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The annotation-style ICDs of the skill's examples load, and decode real bytes the way the legacy
 * annotation mechanism reads them.
 */
class AnnotationJsonIcdLoaderTest {

    /** The examples are shared with the Java converter and the skill; tests run in backend/. */
    private static final Path EXAMPLES = Path.of("..", "skills", "icd-from-annotations", "examples");

    private static IcdDefinition example(String name) throws IOException, IcdLoadException {
        return JsonIcdLoader.load(Files.readAllBytes(EXAMPLES.resolve(name).resolve("expected.icd.json")));
    }

    private static Map<String, FieldValue> decode(IcdDefinition icd, long opcode, byte[] body) throws Exception {
        ByteBuffer message = ByteBuffer.allocate(4 + body.length); // header: UINT16 msgType, UINT16 msgLength
        message.putShort((short) opcode).putShort((short) (4 + body.length)).put(body);
        return new MessageDecoder(icd).decode(icd.message(opcode), message.array()).values();
    }

    private static List<String> problems(String json) {
        IcdLoadException failure = assertThrows(IcdLoadException.class, () -> JsonIcdLoader.load(json.getBytes(StandardCharsets.UTF_8)));
        return failure.problems().stream().map(IcdProblem::toString).toList();
    }

    @ParameterizedTest
    @ValueSource(strings = {"01-basic", "02-arrays", "03-enums-and-checks", "04-read-by-code"})
    void everyExampleLoads(String name) throws Exception {
        IcdDefinition icd = example(name);

        assertEquals(EFieldType.UINT16, icd.header().fields().get(0).type(), "@Unsigned int is 2 bytes on the wire");
        assertEquals(4, icd.header().size());
        assertEquals(1, icd.messages().size());
    }

    @Test
    void bitsShareTheirChunkMostSignificantBitsFirst() throws Exception {
        byte[] body = ByteBuffer.allocate(6).put((byte) 0b10101_101).put("HELLO".getBytes(StandardCharsets.US_ASCII)).array();

        Map<String, FieldValue> fields = decode(example("01-basic"), 1, body);

        assertEquals(21L, fields.get("a").value());
        assertEquals(5L, fields.get("b").value());
        assertEquals("HELLO", fields.get("name").value());
        assertEquals(1L, fields.get("header.msgType").value());
    }

    @Test
    void arraysAreCountedInBytesOrByALengthWrittenBeforeThem() throws Exception {
        ByteBuffer body = ByteBuffer.allocate(4 + 4 + 2 * 10 + 4 * 2 + 1 + 3);
        body.putInt(77).putInt(20);                           // reportId, numOfReports = 20 bytes = 2 tracks
        body.putShort((short) 65000).putInt(12345).putFloat(1.5f); // tracks[0]
        body.putShort((short) 2).putInt(-100).putFloat(2.5f);      // tracks[1]
        body.putShort((short) 1).putShort((short) 2).putShort((short) 3).putShort((short) 4); // spare
        body.put((byte) 3).put(new byte[]{9, 8, 7});          // notes: its length, then 3 bytes

        Map<String, FieldValue> fields = decode(example("02-arrays"), 2, body.array());

        assertEquals(65000L, fields.get("tracks[0].trackId").value(), "@Unsigned int: UINT16");
        assertEquals(123.45, fields.get("tracks[0].range").scaled(), 1e-9);
        assertEquals(-1.0, fields.get("tracks[1].range").scaled(), 1e-9);
        assertEquals(4L, fields.get("spare[3]").value());
        assertEquals(3L, fields.get("notesLength").value());
        assertEquals(7L, fields.get("notes[2]").value());
        assertFalse(fields.containsKey("tracks[2].trackId"));
    }

    @Test
    void enumsFixedPointValidityAndReversedHebrew() throws Exception {
        Charset hebrew = Charset.forName("ISO-8859-8");
        ByteBuffer body = ByteBuffer.allocate(1 + 2 + 4 + 4 + 8 + 1);
        body.put((byte) 2).putShort((short) 20).putInt(3605).putInt(200);
        body.put(new StringBuilder("שלום").reverse().toString().getBytes(hebrew)).put(new byte[4]); // stored last letter first
        body.put((byte) (100 << 1 | 1));                      // priority = 100 (7 bits), alarm = 1

        Map<String, FieldValue> fields = decode(example("03-enums-and-checks"), 3, body.array());

        assertEquals("ACTIVE", fields.get("mode").enumName());
        assertEquals("HIGH", fields.get("level").enumName(), "IntValued: 20 is HIGH");
        assertEquals(360.5, fields.get("azimuth").scaled(), 1e-9);
        assertEquals(EValidity.OUT_OF_RANGE, fields.get("azimuth").validity());
        assertEquals(EValidity.INVALIDITY_VALUE, fields.get("head").validity());
        assertEquals("שלום", fields.get("operator").value());
        assertEquals(100L, fields.get("priority").value());
        assertEquals(1L, fields.get("alarm").value());
    }

    @Test
    void decodingStopsBeforeAFieldThatCodeReads() throws Exception {
        byte[] body = ByteBuffer.allocate(12).putInt(5).putInt(10).putInt(99).array();

        MessageDecoder.DecodedFields fields = new MessageDecoder(example("04-read-by-code"))
                .decode(example("04-read-by-code").message(4), withHeader(4, body));

        assertEquals(10L, fields.values().get("bool").value());
        assertFalse(fields.values().containsKey("name"));
        assertEquals(4, fields.trailingBytes(), "the bytes from 'name' on are kept, not decoded");
    }

    private static byte[] withHeader(long opcode, byte[] body) {
        return ByteBuffer.allocate(4 + body.length).putShort((short) opcode).putShort((short) (4 + body.length)).put(body).array();
    }

    // ------------------------------------------------------------------ problems are reported with their place

    private static String icd(String messageFields) {
        return """
                {"format": "annotations", "id": "t", "name": "t",
                 "header": {"class": "H", "opcodeField": "op", "lengthField": "len"},
                 "messages": [{"opcode": 1, "class": "M"}],
                 "classes": {
                   "H": {"fields": [{"ordinal": 0, "name": "op", "type": "short"}, {"ordinal": 1, "name": "len", "type": "short"}]},
                   "M": {"fields": [%s]}
                 }}""".formatted(messageFields);
    }

    @Test
    void bitsThatDoNotFillTheirChunkAreReported() {
        List<String> problems = problems(icd("""
                {"ordinal": 0, "name": "a", "type": "int", "bits": {"value": 5, "bitfield": "BITS8"}},
                {"ordinal": 1, "name": "b", "type": "int"}"""));

        assertTrue(problems.get(0).startsWith("$.classes.M.fields[0]: the bits fields from here add up to 5 bits"), problems.toString());
    }

    @Test
    void typosDuplicateOrdinalsAndMissingSizesAreReported() {
        List<String> problems = problems(icd("""
                {"ordinal": 0, "name": "a", "type": "int", "ordianl": 3},
                {"ordinal": 0, "name": "b", "type": "String"},
                {"ordinal": 2, "name": "c", "type": "EMissing"}"""));

        assertTrue(problems.stream().anyMatch(p -> p.startsWith("$.classes.M.fields[0].ordianl: unknown property")), problems.toString());
        assertTrue(problems.contains("$.classes.M.fields[1].ordinal: duplicate ordinal 0"), problems.toString());
        assertTrue(problems.contains("$.classes.M.fields[1].size: a String needs its fixed length (@Size)"), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("$.classes.M.fields[2].type: 'EMissing' is not")), problems.toString());
    }

    @Test
    void transientFieldsAreNotOnTheWire() throws Exception {
        IcdDefinition icd = JsonIcdLoader.load(icd("""
                {"name": "aValidity", "type": "boolean", "transient": true},
                {"ordinal": 0, "name": "a", "type": "long", "unsigned": true}""").getBytes(StandardCharsets.UTF_8));

        assertEquals(1, icd.messages().get(0).fields().size());
        assertEquals(EFieldType.UINT32, icd.messages().get(0).fields().get(0).type(), "@Unsigned long is 4 bytes");
    }
}
