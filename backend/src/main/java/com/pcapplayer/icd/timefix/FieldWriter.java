package com.pcapplayer.icd.timefix;

import com.pcapplayer.icd.decode.ByteSpan;
import com.pcapplayer.icd.decode.FieldSlot;
import com.pcapplayer.icd.decode.IcdBytes;

import java.util.List;

/**
 * Writes a value into a field in place, even when the field's bytes are split across packets.
 * <p>
 * The field's bytes are first gathered from their packets into one small array, the value is written
 * there (in the field's byte order; for a bit field only its bits change), then the bytes are put back.
 * Bytes outside the field are never touched.
 */
public final class FieldWriter {

    private FieldWriter() {
    }

    /**
     * @param slot  how the field is stored
     * @param spans where its bytes are, in order; together exactly {@code slot.size()} bytes
     * @param raw   the bits to store (see {@link EncodedValue#raw()})
     */
    public static void write(FieldSlot slot, List<ByteSpan> spans, long raw, StepBytes steps) {
        byte[] container = gather(spans, steps);
        long value = raw;

        if (slot.bitField()) {
            long current = IcdBytes.readUnsigned(container, 0, slot.size(), slot.byteOrder());
            long mask = (slot.bitWidth() == 64 ? -1L : (1L << slot.bitWidth()) - 1) << slot.bitShift();
            value = (current & ~mask) | ((raw << slot.bitShift()) & mask);
        }
        IcdBytes.writeUnsigned(container, 0, slot.size(), slot.byteOrder(), value);
        scatter(container, spans, steps);
    }

    /** The bytes at {@code spans}, concatenated. */
    public static byte[] gather(List<ByteSpan> spans, StepBytes steps) {
        int size = spans.stream().mapToInt(ByteSpan::length).sum();
        byte[] bytes = new byte[size];
        int position = 0;

        for (ByteSpan span : spans) {
            System.arraycopy(steps.bytes(span.step()), span.offset(), bytes, position, span.length());
            position += span.length();
        }
        return bytes;
    }

    /** Writes {@code bytes} back over {@code spans} (the reverse of {@link #gather}). */
    public static void scatter(byte[] bytes, List<ByteSpan> spans, StepBytes steps) {
        int position = 0;

        for (ByteSpan span : spans) {
            System.arraycopy(bytes, position, steps.bytes(span.step()), span.offset(), span.length());
            position += span.length();
        }
    }
}
