package com.pcapplayer.icd.timefix;

import com.pcapplayer.icd.decode.ByteSpan;
import com.pcapplayer.icd.decode.FieldSlot;

import java.util.List;

/**
 * One time field to rewrite at send time.
 *
 * @param path        the field's path in the message, e.g. {@code targets[2].seenGps}
 * @param offsetNanos the field's original time minus the base's original time; 0 for the base itself
 * @param spans       where the field's bytes are in the script steps
 */
public record FieldPatch(String path, ETimeKind kind, FieldSlot slot, List<ByteSpan> spans, long offsetNanos) {
}
