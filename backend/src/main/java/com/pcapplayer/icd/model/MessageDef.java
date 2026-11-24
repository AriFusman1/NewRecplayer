package com.pcapplayer.icd.model;

import java.util.List;

/**
 * One message type. Its fields follow the header.
 *
 * @param byteOrder override for the whole message (header excluded), null to use the ICD's
 */
public record MessageDef(long opcode, String name, EByteOrder byteOrder, List<FieldDef> fields) {
}
