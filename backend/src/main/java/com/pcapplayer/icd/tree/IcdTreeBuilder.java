package com.pcapplayer.icd.tree;

import com.pcapplayer.icd.model.BitDef;
import com.pcapplayer.icd.model.EFieldType;
import com.pcapplayer.icd.model.FieldDef;
import com.pcapplayer.icd.model.IcdDefinition;
import com.pcapplayer.icd.model.MessageDef;

import java.util.ArrayList;
import java.util.List;

/** Turns an {@link IcdDefinition} into an {@link IcdTree}. */
public final class IcdTreeBuilder {

    private IcdTreeBuilder() {
    }

    public static IcdTree of(IcdDefinition icd) {
        List<IcdTreeMessage> messages = new ArrayList<>();
        int headerSize = icd.header().size();

        for (MessageDef message : icd.messages()) {
            List<IcdTreeNode> fields = new ArrayList<>();
            fields.add(new IcdTreeNode("header", "header", ETreeNodeKind.HEADER, "header", null, 0, headerSize, null,
                    false, false, nodes(icd.header().fields(), "header.", false)));
            fields.addAll(nodes(message.fields(), "", false));
            int bodySize = FieldDef.fixedSize(message.fields());
            Integer size = bodySize == FieldDef.VARIABLE ? null : headerSize + bodySize;
            messages.add(new IcdTreeMessage(message.opcode(), message.name(), size, fields));
        }
        return new IcdTree(icd.id(), icd.name(), icd.byteOrder(), messages);
    }

    private static List<IcdTreeNode> nodes(List<FieldDef> fields, String prefix, boolean inArray) {
        List<IcdTreeNode> nodes = new ArrayList<>();
        for (FieldDef field : fields) nodes.add(node(field, prefix, inArray));
        return nodes;
    }

    private static IcdTreeNode node(FieldDef field, String prefix, boolean parentInArray) {
        boolean inArray = parentInArray || field.array() != null;
        String path = prefix + field.name() + (field.array() != null ? "[*]" : "");
        String array = field.array() == null ? null
                : field.array().variable() ? field.array().lengthField() : String.valueOf(field.array().length());
        int elementSize = field.elementSize();
        Integer size = elementSize == FieldDef.VARIABLE ? null : elementSize;

        return switch (field.kind()) {
            case PRIMITIVE -> new IcdTreeNode(field.name(), path, ETreeNodeKind.PRIMITIVE, field.type().name(), field.type(), 0,
                    size, array, field.type().numeric(), inArray, List.of());
            case ENUM -> new IcdTreeNode(field.name(), path, ETreeNodeKind.ENUM,
                    "enum " + field.enumDef().name() + " (" + field.enumDef().type() + ")", field.enumDef().type(), 0,
                    size, array, false, inArray, List.of());
            case STRING -> new IcdTreeNode(field.name(), path, ETreeNodeKind.STRING, "string " + field.string().encoding(), null, 0,
                    size, array, false, inArray, List.of());
            case PADDING -> new IcdTreeNode(field.name(), path, ETreeNodeKind.PADDING, "padding", null, 0, size, null, false,
                    inArray, List.of());
            case STRUCT -> new IcdTreeNode(field.name(), path, ETreeNodeKind.STRUCT, "struct " + field.struct().name(), null, 0,
                    size, array, false, inArray, nodes(field.struct().fields(), path + ".", inArray));
            case BITFIELD -> new IcdTreeNode(field.name(), path, ETreeNodeKind.BITFIELD,
                    "bitfield " + field.bitfield().type() + " " + field.bitfield().bitOrder(), null, 0, size, array, false,
                    inArray, bits(field, path, prefix, inArray));
        };
    }

    private static List<IcdTreeNode> bits(FieldDef field, String path, String prefix, boolean inArray) {
        List<IcdTreeNode> bits = new ArrayList<>();
        EFieldType container = field.bitfield().type();

        for (BitDef bit : field.bitfield().bits()) {
            bits.add(new IcdTreeNode(bit.name(), field.bitfield().bitPath(path, prefix, bit), ETreeNodeKind.BIT, bit.bits() + " bits", container,
                    bit.bits(), null, null, true, inArray, List.of()));
        }
        return bits;
    }
}
