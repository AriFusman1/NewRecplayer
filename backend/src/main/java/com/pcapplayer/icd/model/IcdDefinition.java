package com.pcapplayer.icd.model;

import java.util.List;

/**
 * One version of an ICD (interface control document), whatever it was loaded from (Java annotations or
 * JSON). Messages are sorted by opcode; fields by their order.
 *
 * @param id        stable identifier, used by categories and time-fix profiles; letters, digits, '.', '_', '-'
 * @param byteOrder default for every field
 */
public record IcdDefinition(String id, String name, EByteOrder byteOrder, HeaderDef header, List<MessageDef> messages) {

    /** The message with this opcode, or null. */
    public MessageDef message(long opcode) {
        for (MessageDef message : messages) {
            if (message.opcode() == opcode) return message;
        }
        return null;
    }
}
