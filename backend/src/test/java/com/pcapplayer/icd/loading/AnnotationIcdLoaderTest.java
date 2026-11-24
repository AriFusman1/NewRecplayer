package com.pcapplayer.icd.loading;

import com.pcapplayer.icd.model.EBitOrder;
import com.pcapplayer.icd.model.EByteOrder;
import com.pcapplayer.icd.model.EFieldKind;
import com.pcapplayer.icd.model.EFieldType;
import com.pcapplayer.icd.model.FieldDef;
import com.pcapplayer.icd.model.IcdDefinition;
import com.pcapplayer.icd.model.MessageDef;
import com.pcapplayer.icd.versions.sample.SampleIcd;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnnotationIcdLoaderTest {

    @Test
    void loadsTheSampleIcd() throws IcdLoadException {
        IcdDefinition icd = AnnotationIcdLoader.load(SampleIcd.class);

        assertEquals("sample-v1", icd.id());
        assertEquals(EByteOrder.BIG_ENDIAN, icd.byteOrder());
        assertEquals(List.of("opcode", "length", "sequence", "sendTime"), names(icd.header().fields()));
        assertEquals("opcode", icd.header().opcodeField());
        assertEquals("length", icd.header().lengthField());
        assertEquals(12, icd.header().size());
        assertEquals(List.of(1L, 2L), icd.messages().stream().map(MessageDef::opcode).toList(), "sorted by opcode");
    }

    @Test
    void ordersFieldsByOrderNotByDeclaration() throws IcdLoadException {
        MessageDef status = AnnotationIcdLoader.load(SampleIcd.class).message(1);

        assertEquals(List.of("status", "flags", "reserved", "unitName", "timestampMs", "counter", "healthy"),
                names(status.fields()));
        assertEquals(25, FieldDef.fixedSize(status.fields()));
    }

    @Test
    void readsEveryKindOfField() throws IcdLoadException {
        IcdDefinition icd = AnnotationIcdLoader.load(SampleIcd.class);
        MessageDef status = icd.message(1);
        MessageDef track = icd.message(2);

        FieldDef statusCode = status.fields().get(0);
        assertEquals(EFieldKind.ENUM, statusCode.kind());
        assertEquals("StatusCode", statusCode.enumDef().name(), "the E prefix is dropped");
        assertEquals(Map.of(0L, "OFF", 1L, "STANDBY", 2L, "OPERATIONAL", 255L, "FAULT"), statusCode.enumDef().values());

        FieldDef flags = status.fields().get(1);
        assertEquals(EFieldKind.BITFIELD, flags.kind());
        assertEquals(EBitOrder.MSB_FIRST, flags.bitfield().bitOrder());
        assertEquals(List.of("ready", "mode", "spare"), flags.bitfield().bits().stream().map(bit -> bit.name()).toList());

        assertEquals(EFieldKind.PADDING, status.fields().get(2).kind());
        assertEquals(2, status.fields().get(2).paddingBytes());
        assertEquals(8, status.fields().get(3).string().length());
        assertEquals("US-ASCII", status.fields().get(3).string().encoding());
        assertEquals(EByteOrder.LITTLE_ENDIAN, status.fields().get(5).byteOrder());
        assertEquals(EFieldType.BOOL, status.fields().get(6).type());

        assertEquals(EByteOrder.LITTLE_ENDIAN, track.byteOrder());
        assertEquals(EByteOrder.BIG_ENDIAN, track.fields().get(1).byteOrder());
        assertNull(track.fields().get(0).byteOrder(), "inherits");
        assertEquals(EFieldType.FLOAT64, track.fields().get(2).type(), "inferred from double");

        FieldDef targets = track.fields().get(4);
        assertEquals(EFieldKind.STRUCT, targets.kind());
        assertEquals("targetCount", targets.array().lengthField());
        assertEquals("Target", targets.struct().name());
        assertEquals(17, targets.elementSize());
        assertEquals(FieldDef.VARIABLE, targets.fixedSize());
        assertEquals("Position", targets.struct().fields().get(1).struct().name());
        assertEquals(EFieldType.INT8, targets.struct().fields().get(3).enumDef().type());

        FieldDef samples = track.fields().get(5);
        assertEquals(EFieldType.INT16, samples.type());
        assertEquals(3, samples.array().length());
        assertEquals(6, samples.fixedSize());
    }

    @Test
    void reportsBadAnnotationsWithTheirJavaLocation() {
        IcdLoadException error = assertThrows(IcdLoadException.class, () -> AnnotationIcdLoader.load(BadIcd.class));

        assertHas(error, "BadMessage.values", "@IcdArray");
        assertHas(error, "BadMessage.self", "contains itself");
        assertHas(error, "BadMessage.unknown", "cannot tell the wire type");
    }

    @Test
    void runsTheModelRulesOnAnnotatedIcds() {
        IcdLoadException error = assertThrows(IcdLoadException.class, () -> AnnotationIcdLoader.load(InvalidRulesIcd.class));

        assertHas(error, "InvalidRulesHeader", "opcode field");
        assertHas(error, "InvalidRulesMessage.items", "lengthField 'later'");
    }

    @Test
    void aClassWithoutIcdIsRejected() {
        assertThrows(IcdLoadException.class, () -> AnnotationIcdLoader.load(String.class));
    }

    static void assertHas(IcdLoadException error, String path, String text) {
        assertTrue(error.problems().stream().anyMatch(problem -> problem.path().equals(path) && problem.message().contains(text)),
                "expected a problem at " + path + " containing '" + text + "', got " + error.problems());
    }

    private static List<String> names(List<FieldDef> fields) {
        return fields.stream().map(FieldDef::name).toList();
    }
}
