package com.pcapplayer.icd.timefix;

/**
 * EXTENSION POINT for checksums: runs after a message's time fields were rewritten, before it is sent.
 * <p>
 * No ICD needs it yet. If a message carries a CRC or checksum, implement this to recompute it: read the
 * message with {@code FieldWriter.gather(patch.pieces(), steps)}, compute, and write the checksum field
 * back with {@code FieldWriter.write} (or {@code scatter} the whole message).
 */
@FunctionalInterface
public interface PostPatchHook {

    PostPatchHook NONE = (patch, steps) -> {
    };

    void afterPatch(MessagePatch patch, StepBytes steps);
}
