package com.pcapplayer.icd.tree;

import com.pcapplayer.icd.model.EFieldType;
import com.pcapplayer.testSupport.SampleIcdMessages;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IcdTreeBuilderTest {

    private final IcdTree tree = IcdTreeBuilder.of(SampleIcdMessages.icd());

    @Test
    void messagesStartWithTheHeader() {
        IcdTreeMessage status = tree.message(1);

        assertEquals("Status", status.name());
        assertEquals(37, status.size());
        IcdTreeNode header = status.fields().get(0);
        assertEquals(ETreeNodeKind.HEADER, header.kind());
        assertEquals(12, header.size());
        assertEquals(List.of("header.opcode", "header.length", "header.sequence", "header.sendTime"),
                header.children().stream().map(IcdTreeNode::path).toList());
    }

    @Test
    void fieldsShowTheirTypeSizeAndWhetherTheyCanHoldATime() {
        Map<String, IcdTreeNode> status = tree.message(1).byPath();

        assertNode(status.get("timestampMs"), ETreeNodeKind.PRIMITIVE, "INT64", 8, true);
        assertNode(status.get("status"), ETreeNodeKind.ENUM, "enum StatusCode (UINT8)", 1, false);
        assertNode(status.get("unitName"), ETreeNodeKind.STRING, "string US-ASCII", 8, false);
        assertNode(status.get("reserved"), ETreeNodeKind.PADDING, "padding", 2, false);
        assertNode(status.get("healthy"), ETreeNodeKind.PRIMITIVE, "BOOL", 1, false);
        assertNode(status.get("flags"), ETreeNodeKind.BITFIELD, "bitfield UINT8 MSB_FIRST", 1, false);
        IcdTreeNode mode = status.get("flags.mode");
        assertNode(mode, ETreeNodeKind.BIT, "3 bits", null, true);
        assertEquals(3, mode.bitWidth());
        assertEquals(EFieldType.UINT8, mode.storageType());
    }

    @Test
    void arraysAppearOnceWithStarPaths() {
        IcdTreeMessage track = tree.message(2);
        Map<String, IcdTreeNode> nodes = track.byPath();

        assertNull(track.size(), "depends on targetCount");
        IcdTreeNode targets = nodes.get("targets[*]");
        assertEquals("targetCount", targets.array());
        assertEquals(17, targets.size(), "one element");
        assertTrue(nodes.get("targets[*].pos.lat").inArray());
        assertTrue(nodes.get("targets[*].seenGps").numeric());
        assertEquals("3", nodes.get("samples[*]").array());
        assertTrue(nodes.get("samples[*]").numeric());
        assertFalse(nodes.get("gpsTime").inArray());
    }

    private static void assertNode(IcdTreeNode node, ETreeNodeKind kind, String type, Integer size, boolean numeric) {
        assertEquals(kind, node.kind(), node.path());
        assertEquals(type, node.type(), node.path());
        assertEquals(size, node.size(), node.path());
        assertEquals(numeric, node.numeric(), node.path());
    }
}
