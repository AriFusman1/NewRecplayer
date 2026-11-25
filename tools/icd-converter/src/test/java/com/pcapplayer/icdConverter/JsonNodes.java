package com.pcapplayer.icdConverter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Test helper: a copy of a JSON tree with every object's keys sorted, for order-independent comparison. */
final class JsonNodes {

    private JsonNodes() {
    }

    static JsonNode sorted(JsonNode node) {
        if (node.isArray()) {
            ArrayNode copy = IcdConverter.MAPPER.createArrayNode();
            node.forEach(element -> copy.add(sorted(element)));
            return copy;
        }
        if (!node.isObject()) return node;

        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        Collections.sort(names);
        ObjectNode copy = IcdConverter.MAPPER.createObjectNode();
        names.forEach(name -> copy.set(name, sorted(node.get(name))));
        return copy;
    }
}
