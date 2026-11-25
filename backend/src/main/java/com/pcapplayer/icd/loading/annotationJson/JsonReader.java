package com.pcapplayer.icd.loading.annotationJson;

import com.fasterxml.jackson.databind.JsonNode;
import com.pcapplayer.icd.loading.IcdProblem;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Reads typed values out of JSON objects and collects every problem with its JSON path, so all of them
 * are reported together instead of stopping at the first.
 */
final class JsonReader {

    private final List<IcdProblem> problems = new ArrayList<>();

    List<IcdProblem> problems() {
        return problems;
    }

    void problem(String path, String message) {
        problems.add(new IcdProblem(path, message));
    }

    /** Reports every key of {@code node} that is not in {@code allowed} (catches typos such as "ordianl"). */
    void onlyKeys(JsonNode node, String path, Set<String> allowed) {
        Iterator<String> names = node.fieldNames();

        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) problem(path + "." + name, "unknown property (allowed: " + String.join(", ", allowed) + ")");
        }
    }

    boolean isObject(JsonNode node, String path) {
        if (node != null && node.isObject()) return true;

        problem(path, "must be an object");
        return false;
    }

    String text(JsonNode parent, String key, String path, boolean required) {
        JsonNode node = parent.get(key);

        if (node == null || node.isNull()) {
            if (required) problem(path + "." + key, "is required");
            return null;
        }
        if (node.isTextual() && !node.asText().isBlank()) return node.asText();

        problem(path + "." + key, "must be a non-empty string");
        return null;
    }

    Integer integer(JsonNode parent, String key, String path, boolean required) {
        JsonNode node = parent.get(key);

        if (node == null || node.isNull()) {
            if (required) problem(path + "." + key, "is required");
            return null;
        }
        if (node.canConvertToInt() && node.isIntegralNumber()) return node.asInt();

        problem(path + "." + key, "must be an integer");
        return null;
    }

    Long longValue(JsonNode parent, String key, String path) {
        JsonNode node = parent.get(key);
        if (node == null || node.isNull()) return null;
        if (node.isIntegralNumber() && node.canConvertToLong()) return node.asLong();

        problem(path + "." + key, "must be an integer");
        return null;
    }

    Double number(JsonNode parent, String key, String path) {
        JsonNode node = parent.get(key);
        if (node == null || node.isNull()) return null;
        if (node.isNumber()) return node.asDouble();

        problem(path + "." + key, "must be a number");
        return null;
    }

    boolean flag(JsonNode parent, String key, String path, boolean fallback) {
        JsonNode node = parent.get(key);
        if (node == null || node.isNull()) return fallback;
        if (node.isBoolean()) return node.asBoolean();

        problem(path + "." + key, "must be true or false");
        return fallback;
    }

    <E extends Enum<E>> E enumValue(JsonNode parent, String key, String path, Class<E> type, E fallback) {
        String value = text(parent, key, path, false);
        if (value == null) return fallback;

        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            List<String> names = new ArrayList<>();
            for (E constant : type.getEnumConstants()) names.add(constant.name());
            problem(path + "." + key, "must be one of " + String.join(", ", names) + " (got \"" + value + "\")");
            return fallback;
        }
    }
}
