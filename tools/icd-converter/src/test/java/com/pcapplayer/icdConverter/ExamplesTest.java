package com.pcapplayer.icdConverter;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Each example of the skill converts to exactly its expected JSON. The same expected files are what
 * the skill teaches the model to write, and what the backend tests load and decode.
 */
class ExamplesTest {

    /** Tests run in tools/icd-converter/. */
    private static final Path EXAMPLES = Path.of("..", "..", "skills", "icd-from-annotations", "examples");

    private static IcdConverter.Result convert(String example) throws Exception {
        Path folder = EXAMPLES.resolve(example);
        JsonNode config = IcdConverter.MAPPER.readTree(folder.resolve("input").resolve("icd-config.json").toFile());
        return IcdConverter.convert(folder.resolve("input"), config);
    }

    @ParameterizedTest
    @ValueSource(strings = {"01-basic", "02-arrays", "03-enums-and-checks", "04-read-by-code"})
    void convertsToTheExpectedJson(String example) throws Exception {
        JsonNode expected = IcdConverter.MAPPER.readTree(EXAMPLES.resolve(example).resolve("expected.icd.json").toFile());

        JsonNode actual = convert(example).icd();

        // keys sorted, so a failure shows a readable diff and key order does not matter
        assertEquals(JsonNodes.sorted(expected).toPrettyString(), JsonNodes.sorted(actual).toPrettyString());
    }

    @Test
    void warnsAboutFieldsThatCodeReads() throws Exception {
        List<String> warnings = convert("04-read-by-code").warnings();

        assertEquals(List.of(
                "Lookup.name: @ExistenceEncoded is decided by code; the message is decoded only up to this field",
                "Lookup.tableName: @Custom is read by code; the message is decoded only up to this field"), warnings);
    }

    @Test
    void theOtherExamplesConvertWithoutWarnings() throws Exception {
        for (String example : List.of("01-basic", "02-arrays", "03-enums-and-checks")) {
            assertEquals(List.of(), convert(example).warnings(), example);
        }
    }
}
