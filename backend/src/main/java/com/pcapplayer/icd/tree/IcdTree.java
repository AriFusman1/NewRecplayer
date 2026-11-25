package com.pcapplayer.icd.tree;

import com.pcapplayer.icd.model.EByteOrder;

import java.util.List;

/** The structure of an ICD for the UI: messages, then their fields. Structure only, no values. */
public record IcdTree(String id, String name, EByteOrder byteOrder, List<IcdTreeMessage> messages) {

    /** The message with this opcode, or null. */
    public IcdTreeMessage message(long opcode) {
        return messages.stream().filter(message -> message.opcode() == opcode).findFirst().orElse(null);
    }
}
