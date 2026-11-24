package com.pcapplayer.icd.decode;

import com.pcapplayer.icd.model.ArrayDef;
import com.pcapplayer.icd.model.BitDef;
import com.pcapplayer.icd.model.BitfieldDef;
import com.pcapplayer.icd.model.EBitOrder;
import com.pcapplayer.icd.model.EByteOrder;
import com.pcapplayer.icd.model.EFieldType;
import com.pcapplayer.icd.model.ELengthUnit;
import com.pcapplayer.icd.model.FieldDef;
import com.pcapplayer.icd.model.HeaderDef;
import com.pcapplayer.icd.model.IcdDefinition;
import com.pcapplayer.icd.model.MessageDef;
import com.pcapplayer.testSupport.SampleIcdMessages;
import com.pcapplayer.testSupport.SampleIcdMessages.TargetValues;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageDecoderTest {

    private final IcdDefinition icd = SampleIcdMessages.icd();
    private final MessageDecoder decoder = new MessageDecoder(icd);

    @Test
    void readsTheHeaderForFraming() {
        byte[] status = SampleIcdMessages.status(7, 3600, 1_700_000_000_000L);

        assertEquals(1, decoder.opcode(status, 0));
        assertEquals(37, decoder.messageSize(status, 0));
        assertEquals(12, decoder.headerSize());
    }

    @Test
    void decodesEnumBitfieldPaddingStringAndMixedEndianness() throws MessageDecodeException {
        byte[] status = SampleIcdMessages.status(7, 3600, 1_700_000_000_123L);
        Map<String, FieldValue> values = decoder.decode(icd.message(1), status).values();

        assertEquals(7L, values.get("header.sequence").value());
        assertEquals(3600L, values.get("header.sendTime").value());
        assertEquals("OPERATIONAL", values.get("status").enumName());
        assertEquals(2L, values.get("status").value());
        assertEquals(1L, values.get("flags.ready").value());
        assertEquals(5L, values.get("flags.mode").value());
        assertEquals(3L, values.get("flags.spare").value());
        assertEquals("UNIT-A", values.get("unitName").value());
        assertEquals(1_700_000_000_123L, values.get("timestampMs").value(), "big endian INT64");
        assertEquals(0x01020304L, values.get("counter").value(), "little endian override");
        assertEquals(true, values.get("healthy").value());
        assertTrue(values.keySet().stream().noneMatch(path -> path.startsWith("reserved")), "padding is skipped");
    }

    @Test
    void recordsWhereEveryNumberSits() throws MessageDecodeException {
        byte[] status = SampleIcdMessages.status(7, 3600, 0);
        Map<String, FieldSlot> slots = decoder.decode(icd.message(1), status).slots();

        assertEquals(new FieldSlot(8, 4, EFieldType.UINT32, EByteOrder.BIG_ENDIAN, 0, 0), slots.get("header.sendTime"));
        assertEquals(new FieldSlot(24, 8, EFieldType.INT64, EByteOrder.BIG_ENDIAN, 0, 0), slots.get("timestampMs"));
        assertEquals(new FieldSlot(32, 4, EFieldType.UINT32, EByteOrder.LITTLE_ENDIAN, 0, 0), slots.get("counter"));
        assertEquals(new FieldSlot(13, 1, EFieldType.UINT8, EByteOrder.BIG_ENDIAN, 4, 3), slots.get("flags.mode"), "MSB_FIRST: bits 6..4");
    }

    @Test
    void decodesNestedStructsArraysWithACountFieldAndSignedValues() throws MessageDecodeException {
        byte[] track = SampleIcdMessages.track(1, 100, 3_000_000_000L, 1_700_000_000L, 3723.25,
                new TargetValues(11, 32.5f, 34.75f, (short) -12, 1_000_000L, (byte) 1),
                new TargetValues(12, -1.5f, 0f, (short) 900, 2_000_000L, (byte) -1));
        MessageDecoder.DecodedFields decoded = decoder.decode(icd.message(2), track);
        Map<String, FieldValue> values = decoded.values();

        assertEquals(3_000_000_000L, values.get("gpsTime").value(), "little endian UINT64");
        assertEquals(1_700_000_000L, values.get("epochSeconds").value(), "big endian field in a little endian message");
        assertEquals(3723.25, values.get("ltimeExact").value());
        assertEquals(2L, values.get("targetCount").value());
        assertEquals(11L, values.get("targets[0].id").value());
        assertEquals(32.5, values.get("targets[0].pos.lat").value());
        assertEquals(-12L, values.get("targets[0].pos.alt").value(), "signed INT16");
        assertEquals("AIR", values.get("targets[0].kind").enumName());
        assertEquals(-1L, values.get("targets[1].kind").value(), "signed enum");
        assertEquals("UNKNOWN", values.get("targets[1].kind").enumName());
        assertEquals(2_000_000L, values.get("targets[1].seenGps").value());
        assertEquals(List.of(-1L, 0L, 300L), List.of(values.get("samples[0]").value(), values.get("samples[1]").value(),
                values.get("samples[2]").value()));
        assertEquals(-40L, values.get("temperature").value());
        assertEquals(0, decoded.trailingBytes());
        assertEquals(12 + 21 + 17 + 2 + 10, decoded.slots().get("targets[1].seenGps").offset());
    }

    @Test
    void anEmptyCountedArrayIsFine() throws MessageDecodeException {
        byte[] track = SampleIcdMessages.track(1, 100, 0, 0, 0);
        Map<String, FieldValue> values = decoder.decode(icd.message(2), track).values();

        assertEquals(0L, values.get("targetCount").value());
        assertEquals(-40L, values.get("temperature").value());
    }

    @Test
    void bytesBeyondTheDefinitionAreTrailing() throws MessageDecodeException {
        byte[] status = SampleIcdMessages.concat(SampleIcdMessages.status(1, 1, 1), new byte[]{9, 9, 9});

        assertEquals(3, decoder.decode(icd.message(1), status).trailingBytes());
    }

    @Test
    void aShortMessageOrAnImpossibleCountIsAnError() {
        byte[] status = SampleIcdMessages.status(1, 1, 1);
        byte[] shortStatus = Arrays.copyOf(status, 30);
        byte[] track = SampleIcdMessages.track(1, 1, 0, 0, 0);
        track[12 + 20] = (byte) 200; // targetCount: 200 targets cannot fit

        MessageDecodeException tooShort = assertThrows(MessageDecodeException.class, () -> decoder.decode(icd.message(1), shortStatus));
        assertTrue(tooShort.getMessage().contains("timestampMs"), tooShort.getMessage());
        assertThrows(MessageDecodeException.class, () -> decoder.decode(icd.message(2), track));
    }

    @Test
    void lsbFirstBitfieldsAndLittleEndianHeadersAndWordLengths() throws MessageDecodeException {
        BitfieldDef bits = new BitfieldDef(EFieldType.UINT16, EBitOrder.LSB_FIRST,
                List.of(new BitDef("low", 4), new BitDef("high", 12)));
        HeaderDef header = new HeaderDef(List.of(FieldDef.primitive("op", 1, EFieldType.UINT8),
                FieldDef.primitive("words", 2, EFieldType.UINT8)), "op", "words", false, ELengthUnit.WORDS16, 100);
        MessageDef message = new MessageDef(5, "M", null, List.of(FieldDef.ofBitfield("flags", 1, bits),
                FieldDef.primitive("values", 2, EFieldType.UINT8).withArray(ArrayDef.counted("header.words"))));
        IcdDefinition custom = new IcdDefinition("custom", "Custom", EByteOrder.LITTLE_ENDIAN, header, List.of(message));
        MessageDecoder customDecoder = new MessageDecoder(custom);
        byte[] bytes = {5, 1, (byte) 0x2A, (byte) 0xF1}; // body: one 16-bit word = 0xF12A little endian

        assertEquals(4, customDecoder.messageSize(bytes, 0), "1 word of body + 2 header bytes");
        byte[] withValue = {5, 1, (byte) 0x2A, (byte) 0xF1, 7};
        MessageDecodeException error = assertThrows(MessageDecodeException.class, () -> customDecoder.decode(message, bytes));
        assertTrue(error.getMessage().contains("count 1"), "values[] has 1 element but no byte left: " + error.getMessage());

        Map<String, FieldValue> values = customDecoder.decode(message, withValue).values();
        assertEquals(0xAL, values.get("flags.low").value());
        assertEquals(0xF12L, values.get("flags.high").value());
        assertEquals(7L, values.get("values[0]").value(), "count from header.words");
    }

    @Test
    void largeUnsignedValuesStayExact() {
        assertEquals(new BigInteger("18446744073709551615"), IcdBytes.toValue(-1L, EFieldType.UINT64));
        assertEquals(255L, IcdBytes.toValue(0xFFL, EFieldType.UINT8));
        assertEquals(-1L, IcdBytes.toValue(0xFFL, EFieldType.INT8));
        assertEquals(4294967295L, IcdBytes.toValue(0xFFFFFFFFL, EFieldType.UINT32));
    }

    @Test
    void writesAndReadsBothByteOrders() {
        byte[] bytes = new byte[6];
        IcdBytes.writeUnsigned(bytes, 1, 4, EByteOrder.BIG_ENDIAN, 0x0A0B0C0DL);
        assertEquals(List.of(0, 10, 11, 12, 13, 0), toList(bytes));
        assertEquals(0x0A0B0C0DL, IcdBytes.readUnsigned(bytes, 1, 4, EByteOrder.BIG_ENDIAN));

        IcdBytes.writeUnsigned(bytes, 1, 4, EByteOrder.LITTLE_ENDIAN, 0x0A0B0C0DL);
        assertEquals(List.of(0, 13, 12, 11, 10, 0), toList(bytes));
        assertEquals(0x0A0B0C0DL, IcdBytes.readUnsigned(bytes, 1, 4, EByteOrder.LITTLE_ENDIAN));
    }

    private static List<Integer> toList(byte[] bytes) {
        List<Integer> list = new ArrayList<>();
        for (byte value : bytes) list.add(value & 0xFF);
        return list;
    }
}
