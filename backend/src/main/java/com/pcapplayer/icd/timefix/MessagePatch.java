package com.pcapplayer.icd.timefix;

import com.pcapplayer.icd.decode.ByteSpan;

import java.util.List;

/**
 * The time fields of one message to rewrite when its first packet is sent. Everything except the
 * final arithmetic is computed before playback: the fields' positions and their offsets from the base.
 *
 * @param firstStep script step of the message's first byte: the patch is applied when it is sent
 * @param lastStep  script step of the message's last byte
 * @param pieces    where all the message's bytes are (for a {@link PostPatchHook})
 * @param fields    the base field first, then the fields to fix
 */
public record MessagePatch(long opcode, String name, int firstStep, int lastStep, List<ByteSpan> pieces,
                           List<FieldPatch> fields, PostPatchHook hook) {

    /**
     * Writes every field for a message sent now: the base gets {@code now}, every other field
     * {@code now + its offset}, each encoded in its own kind and type.
     *
     * @param nowNanos the real wall-clock time (epoch nanos, UTC) the message's first packet is sent
     */
    public void apply(long nowNanos, StepBytes steps, TimeFixStats stats) {
        for (FieldPatch field : fields) {
            EncodedValue encoded = TimeCodec.encode(nowNanos + field.offsetNanos(), field.kind(), field.slot().type(),
                    field.slot().bitWidth());
            if (encoded.clamped()) stats.clamped(name, field.path(), field.slot().type());
            FieldWriter.write(field.slot(), field.spans(), encoded.raw(), steps);
        }
        hook.afterPatch(this, steps);
        stats.patched();
    }
}
