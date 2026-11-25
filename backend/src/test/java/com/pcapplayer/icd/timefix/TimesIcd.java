package com.pcapplayer.icd.timefix;

import com.pcapplayer.icd.model.EByteOrder;
import com.pcapplayer.icd.model.EFieldType;
import com.pcapplayer.icd.model.ELengthUnit;
import com.pcapplayer.icd.model.FieldDef;
import com.pcapplayer.icd.model.HeaderDef;
import com.pcapplayer.icd.model.IcdDefinition;
import com.pcapplayer.icd.model.MessageDef;
import com.pcapplayer.pcapProcessing.script.EConnectionMode;
import com.pcapplayer.pcapProcessing.script.ReplayScript;
import com.pcapplayer.pcapProcessing.script.ScriptStep;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * A test ICD with one message (opcode 7, 32 bytes, big endian) holding one field of each time kind:
 * header opcode u16 + length u16, then {@code s} INT64 (seconds), {@code ms} INT64 (millis),
 * {@code lt} FLOAT64 (LTime), {@code gps} UINT32 (GPS ticks).
 */
final class TimesIcd {

    static final int SIZE = 32;

    private TimesIcd() {
    }

    static IcdDefinition icd() {
        HeaderDef header = new HeaderDef(List.of(FieldDef.primitive("opcode", 1, EFieldType.UINT16),
                FieldDef.primitive("length", 2, EFieldType.UINT16)), "opcode", "length", true, ELengthUnit.BYTES, 1000);
        MessageDef times = new MessageDef(7, "Times", null, List.of(
                FieldDef.primitive("s", 1, EFieldType.INT64),
                FieldDef.primitive("ms", 2, EFieldType.INT64),
                FieldDef.primitive("lt", 3, EFieldType.FLOAT64),
                FieldDef.primitive("gps", 4, EFieldType.UINT32)));
        return new IcdDefinition("times", "Times", EByteOrder.BIG_ENDIAN, header, List.of(times));
    }

    static byte[] message(long seconds, long millis, double ltime, long gpsTicks) {
        return ByteBuffer.allocate(SIZE).putShort((short) 7).putShort((short) SIZE)
                .putLong(seconds).putLong(millis).putDouble(ltime).putInt((int) gpsTicks).array();
    }

    /** A script sending each message as one packet, 1 ms apart. */
    static ReplayScript script(byte[]... messages) {
        List<ScriptStep> steps = new ArrayList<>();
        for (byte[] message : messages) steps.add(new ScriptStep.Send(steps.size() * 1_000_000L, message));
        return new ReplayScript(EConnectionMode.UDP_SERVER, steps, steps.size(), 0, steps.size() * 1_000_000L);
    }
}
