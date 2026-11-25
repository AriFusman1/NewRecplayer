package com.pcapplayer.icd.decode;

import com.pcapplayer.icd.model.EByteOrder;
import com.pcapplayer.icd.model.FieldDef;
import com.pcapplayer.icd.model.HeaderDef;
import com.pcapplayer.icd.model.IcdDefinition;
import com.pcapplayer.icd.model.MessageDef;

import java.util.Map;

/**
 * Decodes complete messages of one ICD. Stateless and thread-safe; the framing (finding where messages
 * start and end in a stream) is {@link FlowFramer}'s job.
 */
public final class MessageDecoder {

    /**
     * The fields of one message.
     *
     * @param values        path → value, in message order
     * @param slots         path → where the value sits (numbers and bit fields; not strings)
     * @param trailingBytes bytes after the last field that the ICD does not describe
     */
    public record DecodedFields(Map<String, FieldValue> values, Map<String, FieldSlot> slots, int trailingBytes) {
    }

    private final IcdDefinition icd;
    private final int headerSize;
    private final FieldSlot opcodeSlot;
    private final FieldSlot lengthSlot;

    public MessageDecoder(IcdDefinition icd) {
        this.icd = icd;
        this.headerSize = icd.header().size();
        this.opcodeSlot = headerSlot(icd, icd.header().opcodeField());
        this.lengthSlot = headerSlot(icd, icd.header().lengthField());
    }

    public IcdDefinition icd() {
        return icd;
    }

    public int headerSize() {
        return headerSize;
    }

    /** The opcode of the message whose header starts at {@code offset}. */
    public long opcode(byte[] bytes, int offset) {
        return read(bytes, offset, opcodeSlot);
    }

    /**
     * The total size in bytes (header included) of the message whose header starts at {@code offset},
     * from its length field; -1 if the value cannot be a size.
     */
    public long messageSize(byte[] bytes, int offset) {
        HeaderDef header = icd.header();
        long length = read(bytes, offset, lengthSlot);
        if (length < 0 || length > Integer.MAX_VALUE) return -1;

        long size = length * header.lengthUnit().bytes();
        return header.lengthIncludesHeader() ? size : size + headerSize;
    }

    /** Decodes one whole message (header and body). */
    public DecodedFields decode(MessageDef message, byte[] bytes) throws MessageDecodeException {
        MessageWalk walk = new MessageWalk(bytes);
        walk.readFields(icd.header().fields(), "header.", icd.byteOrder());
        EByteOrder bodyOrder = message.byteOrder() != null ? message.byteOrder() : icd.byteOrder();
        walk.readFields(message.fields(), "", bodyOrder);
        return new DecodedFields(walk.values, walk.slots, bytes.length - walk.position());
    }

    private static long read(byte[] bytes, int offset, FieldSlot slot) {
        long raw = IcdBytes.readUnsigned(bytes, offset + slot.offset(), slot.size(), slot.byteOrder());
        return slot.type().signed() ? IcdBytes.signExtend(raw, slot.size()) : raw;
    }

    /** Where a top-level header field sits (header fields before it have fixed sizes). */
    private static FieldSlot headerSlot(IcdDefinition icd, String name) {
        int offset = 0;

        for (FieldDef field : icd.header().fields()) {
            if (field.name().equals(name)) {
                EByteOrder byteOrder = field.byteOrder() != null ? field.byteOrder() : icd.byteOrder();
                return FieldSlot.whole(offset, field.type(), byteOrder);
            }
            offset += field.fixedSize();
        }
        throw new IllegalArgumentException("header field " + name + " not found"); // ruled out by the ICD validator
    }
}
