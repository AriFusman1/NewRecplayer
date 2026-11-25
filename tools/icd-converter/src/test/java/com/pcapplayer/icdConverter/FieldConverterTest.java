package com.pcapplayer.icdConverter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.FieldDeclaration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Each legacy annotation becomes its JSON property, with its parameters as written. */
class FieldConverterTest {

    private final List<String> warnings = new ArrayList<>();

    /** The JSON of the one field declared in {@code body}. */
    private Optional<ObjectNode> convert(String body) {
        FieldDeclaration declaration = StaticJavaParser.parse("class X { " + body + " }").findFirst(FieldDeclaration.class).orElseThrow();
        return new FieldConverter(warnings).convert(declaration, declaration.getVariable(0), "X.f");
    }

    private JsonNode json(String body) {
        return convert(body).orElseThrow();
    }

    private static JsonNode parse(String json) throws Exception {
        return IcdConverter.MAPPER.readTree(json);
    }

    @Test
    void ordinalNameAndJavaType() throws Exception {
        assertEquals(parse("{\"name\":\"f\",\"type\":\"long\",\"ordinal\":3}"), json("@Ordinal(3) public long f;"));
    }

    @Test
    void unsignedAndBitsWithTheChunkNormalized() throws Exception {
        JsonNode field = json("@Ordinal(1) @Unsigned @Bits(bitfieldLength = BitfieldLength.BIT32, value = 13) int f;");

        assertEquals(parse("{\"value\":13,\"bitfield\":\"BITS32\"}"), field.get("bits"));
        assertTrue(field.get("unsigned").asBoolean());
    }

    @Test
    void bitSizeAndEnumerable() throws Exception {
        assertTrue(json("@Ordinal(1) @Bit boolean f;").get("bit").asBoolean());
        assertEquals(8, json("@Ordinal(1) @Size(8) String f;").get("size").asInt());
        assertEquals("byte", json("@Ordinal(1) @Enumerable(byte.class) EMode f;").get("enumerable").asText());
        assertTrue(!json("@Ordinal(1) @Enumerable @Bits(value = 8, bitfield = BitfieldLength.BITS8) EMode f;").has("enumerable"),
                "a bare @Enumerable is sized by its @Bits");
    }

    @Test
    void fixedPointValidRangeAndInvalidityValue() throws Exception {
        JsonNode field = json("@Ordinal(1) @FixedPoint(lsb = 0.1, type = int.class) @ValidRange(minValue = -90, MaxValue = 90)"
                + " @InvalidityValue(200) double f;");

        assertEquals(parse("{\"lsb\":0.1,\"type\":\"int\"}"), field.get("fixedPoint"));
        assertEquals(parse("{\"minValue\":-90,\"maxValue\":90}"), field.get("validRange"), "MaxValue spelled either way");
        assertEquals(200, field.get("invalidityValue").asInt());
    }

    @Test
    void lengthEncoderAndLengthEncodedWithTheNameTrimmed() throws Exception {
        assertEquals(parse("{\"isLengthInBytes\":true}"),
                json("@Ordinal(4) @LenghtEncoder(isLengthInBytes = true) @BinaryInternal public int n;").get("lengthEncoder"));
        assertEquals(parse("{\"encodingField\":\"n\"}"),
                json("@Ordinal(8) @LengthEncoded(encodingField = \"n \") public Track[] f;").get("lengthEncoded"));
        assertEquals(parse("{\"value\":16,\"unsigned\":true}"),
                json("@Ordinal(8) @LengthEncoded(value = 16, unsigned = true) byte[] f;").get("lengthEncoded"));
    }

    @Test
    void staticTransientAndUnorderedFieldsAreNotOnTheWire() {
        assertTrue(convert("public static final int sizeof = 4;").isEmpty());
        assertTrue(convert("@Transient private boolean fValidity;").isEmpty());
        assertTrue(convert("private transient int cache;").isEmpty());
        assertTrue(convert("public int forgotten;").isEmpty());

        assertEquals(List.of("X.f: no @Ordinal, so it is not on the wire; skipped"), warnings);
    }

    @Test
    void fieldsReadByCodeAreKeptWithAWarning() throws Exception {
        JsonNode field = json("@Ordinal(5) @Encoded(codec = TimeCodec.class, underlyingType = int.class) Date f;");

        assertEquals(parse("{\"codec\":\"TimeCodec\",\"underlyingType\":\"int\"}"), field.get("encoded"));
        assertEquals(List.of("X.f: @Encoded is read by a codec; the message is decoded only up to this field"), warnings);
    }

    @Test
    void unknownAnnotationsAreReported() {
        convert("@Ordinal(1) @Frobnicate int f;");

        assertEquals(List.of("X.f: unknown annotation @Frobnicate ignored"), warnings);
    }

    @Test
    void theJavadocBecomesTheDescription() {
        assertEquals("Degrees, the stored int times 0.1.", json("/** Degrees,\n * the stored int times 0.1. */ @Ordinal(1) int f;")
                .get("description").asText());
    }
}
