package com.pcapplayer.icdConverter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Converts annotated Java classes into an annotation-style ICD JSON
 * ({@code "format": "annotations"}, docs/icd-annotations-json.md).
 * <p>
 * The annotations do not say which class is the header or what each message's opcode is, so a small
 * config gives that ({@code id}, {@code name}, {@code header}, {@code messages}); it is copied into the
 * result. The classes are those the header and messages use, directly or through their fields.
 */
public final class IcdConverter {

    /** The ICD, and what the person converting should look at (fields skipped, decoding cut short, ...). */
    public record Result(ObjectNode icd, List<String> warnings) {
    }

    static final ObjectMapper MAPPER = new ObjectMapper();

    private IcdConverter() {
    }

    /**
     * @param sources a folder of .java files (sub-folders included)
     * @param config  the config: {@code id}, {@code name}, {@code header} and {@code messages}
     */
    public static Result convert(Path sources, JsonNode config) throws IOException {
        List<String> warnings = new ArrayList<>();
        SourceIndex index = SourceIndex.of(sources, warnings);
        ClassConverter converter = new ClassConverter(warnings);

        ObjectNode icd = MAPPER.createObjectNode();
        icd.put("format", "annotations");
        config.fields().forEachRemaining(entry -> icd.set(entry.getKey(), entry.getValue()));
        ObjectNode classes = icd.putObject("classes");
        ObjectNode enums = MAPPER.createObjectNode();

        Deque<String> toConvert = new ArrayDeque<>(rootClasses(config));
        Set<String> seen = new LinkedHashSet<>(toConvert);

        while (!toConvert.isEmpty()) {
            String name = toConvert.removeFirst();
            Optional<TypeDeclaration<?>> type = index.find(name);

            if (type.isEmpty()) {
                warnings.add(name + ": no class or enum with this name in " + sources);
            } else if (type.get() instanceof EnumDeclaration enumDeclaration) {
                enums.set(name, converter.convertEnum(enumDeclaration));
            } else {
                ObjectNode converted = converter.convertClass(type.get());
                classes.set(name, converted);
                for (String used : usedTypes(converted)) {
                    if (seen.add(used)) toConvert.addLast(used);
                }
            }
        }
        if (!enums.isEmpty()) icd.set("enums", enums);
        return new Result(icd, warnings);
    }

    /** The header class, then each message's class, in config order. */
    private static List<String> rootClasses(JsonNode config) {
        List<String> names = new ArrayList<>();
        String header = config.path("header").path("class").asText("");
        if (!header.isEmpty()) names.add(header);
        config.path("messages").forEach(message -> names.add(message.path("class").asText("")));
        names.removeIf(String::isEmpty);
        return names;
    }

    /** The class and enum names a converted class's fields use (Java built-in types left out). */
    private static List<String> usedTypes(ObjectNode converted) {
        List<String> used = new ArrayList<>();

        for (JsonNode field : converted.path("fields")) {
            String type = field.path("type").asText().replace("[]", "");
            if (!JavaBuiltIns.isBuiltIn(type) && !field.has("custom") && !field.has("encoded")) used.add(type);
        }
        return used;
    }
}
