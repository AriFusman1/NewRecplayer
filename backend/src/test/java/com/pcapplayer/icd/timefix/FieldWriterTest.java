package com.pcapplayer.icd.timefix;

import com.pcapplayer.icd.decode.ByteSpan;
import com.pcapplayer.icd.decode.FieldSlot;
import com.pcapplayer.icd.model.EByteOrder;
import com.pcapplayer.icd.model.EFieldType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class FieldWriterTest {

    @Test
    void aBigEndianFieldAcrossTwoPacketsIsWrittenInBoth() {
        byte[][] packets = {{1, 2, 3, 4, 5}, {6, 7, 8, 9}};
        FieldSlot slot = FieldSlot.whole(0, EFieldType.UINT32, EByteOrder.BIG_ENDIAN);

        FieldWriter.write(slot, List.of(new ByteSpan(0, 3, 2), new ByteSpan(1, 0, 2)), 0xA1B2C3D4L, step -> packets[step]);

        assertArrayEquals(new byte[]{1, 2, 3, (byte) 0xA1, (byte) 0xB2}, packets[0]);
        assertArrayEquals(new byte[]{(byte) 0xC3, (byte) 0xD4, 8, 9}, packets[1]);
    }

    @Test
    void aLittleEndianFieldAcrossTwoPackets() {
        byte[][] packets = {{1, 2, 3}, {4, 5, 6, 7, 8}};
        FieldSlot slot = FieldSlot.whole(0, EFieldType.UINT32, EByteOrder.LITTLE_ENDIAN);

        FieldWriter.write(slot, List.of(new ByteSpan(0, 2, 1), new ByteSpan(1, 0, 3)), 0xA1B2C3D4L, step -> packets[step]);

        assertArrayEquals(new byte[]{1, 2, (byte) 0xD4}, packets[0]);
        assertArrayEquals(new byte[]{(byte) 0xC3, (byte) 0xB2, (byte) 0xA1, 7, 8}, packets[1]);
    }

    @Test
    void aBitFieldChangesOnlyItsBits() {
        byte[][] packets = {{(byte) 0xFF, (byte) 0b1010_1010, (byte) 0xFF}};
        FieldSlot slot = new FieldSlot(0, 1, EFieldType.UINT8, EByteOrder.BIG_ENDIAN, 4, 3); // bits 6..4

        FieldWriter.write(slot, List.of(new ByteSpan(0, 1, 1)), 0b011, step -> packets[step]);

        assertArrayEquals(new byte[]{(byte) 0xFF, (byte) 0b1011_1010, (byte) 0xFF}, packets[0]);
    }

    @Test
    void aSixteenBitContainerAcrossPacketsLittleEndian() {
        byte[][] packets = {{0x34}, {0x12}}; // container 0x1234, little endian
        FieldSlot slot = new FieldSlot(0, 2, EFieldType.UINT16, EByteOrder.LITTLE_ENDIAN, 4, 8); // bits 11..4 = 0x23

        FieldWriter.write(slot, List.of(new ByteSpan(0, 0, 1), new ByteSpan(1, 0, 1)), 0xAB, step -> packets[step]);

        assertArrayEquals(new byte[]{(byte) 0xB4}, packets[0], "0x1AB4, low byte first");
        assertArrayEquals(new byte[]{0x1A}, packets[1]);
    }
}
