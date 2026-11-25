package com.pcapplayer.icd.loading;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pcapplayer.common.Json;
import com.pcapplayer.icd.model.IcdDefinition;
import com.pcapplayer.icd.versions.sample.SampleIcd;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import static com.pcapplayer.icd.loading.AnnotationIcdLoaderTest.assertHas;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonIcdLoaderTest {

    static byte[] sampleJson() throws IOException {
        try (InputStream in = JsonIcdLoaderTest.class.getResourceAsStream("/icd/sample-icd.json")) {
            return in.readAllBytes();
        }
    }

    @Test
    void jsonAndAnnotationsGiveTheSameModel() throws Exception {
        IcdDefinition fromJson = JsonIcdLoader.load(sampleJson());
        IcdDefinition fromAnnotations = AnnotationIcdLoader.load(SampleIcd.class);

        assertEquals(fromAnnotations, fromJson);
    }

    @Test
    void theDocumentedExampleIsTheTestedSample() throws IOException {
        String doc = Files.readString(Path.of("../docs/icd-json-format.md"), StandardCharsets.UTF_8);
        String sample = new String(sampleJson(), StandardCharsets.UTF_8).strip();

        assertTrue(doc.contains(sample), "docs/icd-json-format.md must contain src/test/resources/icd/sample-icd.json verbatim");
    }

    @Test
    void theSchemaIsValidJson() throws IOException {
        try (InputStream in = JsonIcdLoader.class.getResourceAsStream("/icd/icd.schema.json")) {
            assertTrue(Json.MAPPER.readTree(in).has("$defs"));
        }
    }

    @Test
    void reportsSyntaxErrorsWithTheLine() {
        IcdLoadException error = assertThrows(IcdLoadException.class,
                () -> JsonIcdLoader.load("{\n  \"id\": \"x\",\n  oops\n}".getBytes(StandardCharsets.UTF_8)));

        assertEquals("$", error.problems().get(0).path());
        assertTrue(error.problems().get(0).message().contains("line 3"), error.problems().toString());
    }

    @Test
    void reportsShapeProblemsWithTheirJsonPath() {
        IcdLoadException error = invalid(root -> {
            root.remove("name");
            root.put("colour", "red");
            message(root, 0).put("opcode", "sixteen");
            field(root, 0, 4).put("type", "UINT12");
            field(root, 0, 1).remove("bitfield");
            field(root, 1, 4).put("struct", "Nothing");
            field(root, 1, 5).put("enum", "StatusCode"); // now has both type and enum
        });

        assertHas(error, "$.name", "is required");
        assertHas(error, "$.colour", "unknown property");
        assertHas(error, "$.messages[0].opcode", "must be an integer");
        assertHas(error, "$.messages[0].fields[4].type", "must be one of");
        assertHas(error, "$.messages[0].fields[1]", "needs one of");
        assertHas(error, "$.messages[1].fields[4].struct", "unknown struct 'Nothing'");
        assertHas(error, "$.messages[1].fields[5]", "more than one of type, enum");
    }

    @Test
    void reportsModelRuleProblemsWithTheirJsonPath() {
        IcdLoadException error = invalid(root -> {
            message(root, 1).put("opcode", 1);                                             // duplicate opcode
            ((ObjectNode) field(root, 1, 4).get("array")).put("lengthField", "temperature"); // a later field
            ((ObjectNode) field(root, 0, 1).get("bitfield")).withArray("bits").addObject().put("name", "extra").put("bits", 1);
            ((ObjectNode) root.get("enums").get("StatusCode").get("values")).put("300", "TOO_BIG");
            ((ObjectNode) field(root, 0, 3).get("string")).put("encoding", "NO-SUCH-CHARSET");
            ((ObjectNode) root.get("header").withArray("fields").get(1)).remove("role");
        });

        assertHas(error, "$.messages[1]", "duplicate opcode 1");
        assertHas(error, "$.messages[1].fields[4]", "lengthField 'temperature'");
        assertHas(error, "$.messages[0].fields[1]", "take 9 bits but UINT8 has only 8");
        assertHas(error, "$.messages[0].fields[0]", "value 300 does not fit UINT8");
        assertHas(error, "$.messages[0].fields[3]", "unknown encoding");
        assertHas(error, "$.header", "length field");
    }

    @Test
    void reportsAStructThatContainsItself() {
        IcdLoadException error = invalid(root -> {
            ArrayNode positionFields = ((ObjectNode) root.get("structs").get("Position")).withArray("fields");
            positionFields.addObject().put("name", "again").put("struct", "Target");
        });

        assertHas(error, "$.structs.Target", "contains itself");
    }

    @Test
    void acceptsOpcodesAsNumbersOrHexStrings() {
        assertEquals(16L, JsonIcdLoader.parseNumber("0x10"));
        assertEquals(16L, JsonIcdLoader.parseNumber("16"));
        assertEquals(-1L, JsonIcdLoader.parseNumber("0xFFFFFFFFFFFFFFFF"), "UINT64 max kept as the raw long");
        assertEquals(null, JsonIcdLoader.parseNumber("0x1FFFFFFFFFFFFFFFF"));
        assertEquals(null, JsonIcdLoader.parseNumber("ten"));
    }

    private static IcdLoadException invalid(Consumer<ObjectNode> change) {
        return assertThrows(IcdLoadException.class, () -> {
            ObjectNode root = (ObjectNode) Json.MAPPER.readTree(sampleJson());
            change.accept(root);
            JsonIcdLoader.load(Json.MAPPER.writeValueAsBytes(root));
        });
    }

    private static ObjectNode message(ObjectNode root, int index) {
        return (ObjectNode) root.get("messages").get(index);
    }

    private static ObjectNode field(ObjectNode root, int messageIndex, int fieldIndex) {
        return (ObjectNode) message(root, messageIndex).get("fields").get(fieldIndex);
    }
}
