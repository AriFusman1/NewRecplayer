package com.pcapplayer.icd.tree;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One message type as a tree. The first node is the header.
 *
 * @param size total bytes (header included), null when it depends on an array count
 */
public record IcdTreeMessage(long opcode, String name, Integer size, List<IcdTreeNode> fields) {

    /** Every node by its path. */
    public Map<String, IcdTreeNode> byPath() {
        Map<String, IcdTreeNode> nodes = new LinkedHashMap<>();
        Deque<IcdTreeNode> pending = new ArrayDeque<>(fields);

        while (!pending.isEmpty()) {
            IcdTreeNode node = pending.removeFirst();
            nodes.put(node.path(), node);
            pending.addAll(node.children());
        }
        return nodes;
    }
}
