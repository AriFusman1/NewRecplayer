package com.pcapplayer.icd.decode;

import com.pcapplayer.icd.model.BitDef;
import com.pcapplayer.icd.model.EBitOrder;
import com.pcapplayer.icd.model.EByteOrder;
import com.pcapplayer.icd.model.EFieldKind;
import com.pcapplayer.icd.model.EFieldType;
import com.pcapplayer.icd.model.FieldDef;

import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the fields of one message from its bytes, front to back, recording each value and where it sits.
 * One walk per message; not reusable.
 */
final class MessageWalk {

    private static final String HEADER_PREFIX = "header.";

    private final byte[] bytes;
    private int position;
    final Map<String, FieldValue> values = new LinkedHashMap<>();
    final Map<String, FieldSlot> slots = new LinkedHashMap<>();

    MessageWalk(byte[] bytes) {
        this.bytes = bytes;
    }

    int position() {
        return position;
    }

    /**
     * Reads {@code fields} in order.
     *
     * @param prefix    path prefix of the enclosing struct, e.g. "" or "header." or "targets[2]."
     * @param byteOrder the byte order the fields inherit
     */
    void readFields(List<FieldDef> fields, String prefix, EByteOrder byteOrder) throws MessageDecodeException {
        for (FieldDef field : fields) readField(field, prefix, byteOrder);
    }

    private void readField(FieldDef field, String prefix, EByteOrder inherited) throws MessageDecodeException {
        EByteOrder byteOrder = field.byteOrder() != null ? field.byteOrder() : inherited;

        if (field.array() == null) {
            readElement(field, prefix + field.name(), byteOrder);
            return;
        }
        long count = field.array().variable() ? count(field, prefix) : field.array().length();
        for (int i = 0; i < count; i++) readElement(field, prefix + field.name() + "[" + i + "]", byteOrder);
    }

    private void readElement(FieldDef field, String path, EByteOrder byteOrder) throws MessageDecodeException {
        switch (field.kind()) {
            case PRIMITIVE -> readNumber(path, field.type(), byteOrder);

            case ENUM -> {
                FieldValue number = readNumber(path, field.enumDef().type(), byteOrder);
                String name = field.enumDef().values().get(number.asLong());
                values.put(path, new FieldValue(EFieldKind.ENUM, number.type(), number.value(), name));
            }
            case STRING -> readString(path, field);
            case BITFIELD -> readBitfield(path, field, byteOrder);

            case PADDING -> {
                require(field.paddingBytes(), path);
                position += field.paddingBytes();
            }
            case STRUCT -> readFields(field.struct().fields(), path + ".", byteOrder);
        }
    }

    private FieldValue readNumber(String path, EFieldType type, EByteOrder byteOrder) throws MessageDecodeException {
        require(type.size(), path);
        long raw = IcdBytes.readUnsigned(bytes, position, type.size(), byteOrder);
        FieldValue value = new FieldValue(EFieldKind.PRIMITIVE, type, IcdBytes.toValue(raw, type), null);
        values.put(path, value);
        slots.put(path, FieldSlot.whole(position, type, byteOrder));
        position += type.size();
        return value;
    }

    /** The text ends at the first zero byte; the rest is padding. */
    private void readString(String path, FieldDef field) throws MessageDecodeException {
        int length = field.string().length();
        require(length, path);
        int end = position;
        while (end < position + length && bytes[end] != 0) end++;
        String text = new String(bytes, position, end - position, Charset.forName(field.string().encoding()));
        values.put(path, new FieldValue(EFieldKind.STRING, null, text, null));
        position += length;
    }

    /**
     * Each bit field is cut out of the container: MSB_FIRST fills from the top bit down, LSB_FIRST from
     * bit 0 up. Paths are {@code <field>.<bit name>}.
     */
    private void readBitfield(String path, FieldDef field, EByteOrder byteOrder) throws MessageDecodeException {
        EFieldType container = field.bitfield().type();
        require(container.size(), path);
        long raw = IcdBytes.readUnsigned(bytes, position, container.size(), byteOrder);
        int containerBits = container.size() * 8;
        int used = 0;

        for (BitDef bit : field.bitfield().bits()) {
            int shift = field.bitfield().bitOrder() == EBitOrder.MSB_FIRST ? containerBits - used - bit.bits() : used;
            long mask = bit.bits() == 64 ? -1L : (1L << bit.bits()) - 1;
            String bitPath = path + "." + bit.name();
            values.put(bitPath, new FieldValue(EFieldKind.BITFIELD, container, (raw >>> shift) & mask, null));
            slots.put(bitPath, new FieldSlot(position, container.size(), container, byteOrder, shift, bit.bits()));
            used += bit.bits();
        }
        position += container.size();
    }

    /**
     * The element count of an array, read from a field decoded earlier. A plain name is a sibling in
     * the same struct; {@code header.x} and dotted paths are taken from the message root.
     */
    private long count(FieldDef field, String prefix) throws MessageDecodeException {
        String lengthField = field.array().lengthField();
        boolean fromRoot = lengthField.startsWith(HEADER_PREFIX) || lengthField.contains(".");
        String path = fromRoot ? lengthField : prefix + lengthField;
        FieldValue count = values.get(path);
        if (count == null) throw new MessageDecodeException("array " + prefix + field.name() + ": count field " + path + " not decoded yet");

        long elements = count.asLong();

        if (elements < 0 || elements > bytes.length - position) {
            throw new MessageDecodeException("array " + prefix + field.name() + ": count " + elements + " is impossible ("
                    + (bytes.length - position) + " bytes left)");
        }
        return elements;
    }

    private void require(int size, String path) throws MessageDecodeException {
        if (position + size > bytes.length) {
            throw new MessageDecodeException("the message ends inside " + path + " (needs " + (position + size)
                    + " bytes, has " + bytes.length + ")");
        }
    }
}
