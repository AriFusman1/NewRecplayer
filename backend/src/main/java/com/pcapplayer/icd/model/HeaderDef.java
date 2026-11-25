package com.pcapplayer.icd.model;

import java.util.List;

/**
 * The header every message starts with. Its size must be fixed.
 *
 * @param opcodeField          name of the top-level integer field that identifies the message type
 * @param lengthField          name of the top-level integer field that gives the message length
 * @param lengthIncludesHeader true if the length counts the header too, false if only the body
 * @param maxMessageBytes      a larger length is treated as corrupt data (used to resynchronize)
 */
public record HeaderDef(List<FieldDef> fields, String opcodeField, String lengthField,
                        boolean lengthIncludesHeader, ELengthUnit lengthUnit, int maxMessageBytes) {

    public static final int DEFAULT_MAX_MESSAGE_BYTES = 65_535;

    /** Header size in bytes ({@link FieldDef#VARIABLE} only in an invalid ICD). */
    public int size() {
        return FieldDef.fixedSize(fields);
    }
}
