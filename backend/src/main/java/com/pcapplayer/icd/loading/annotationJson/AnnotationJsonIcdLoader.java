package com.pcapplayer.icd.loading.annotationJson;

import com.fasterxml.jackson.databind.JsonNode;
import com.pcapplayer.icd.loading.IcdLoadException;
import com.pcapplayer.icd.loading.IcdModelValidator;
import com.pcapplayer.icd.loading.IcdProblem;
import com.pcapplayer.icd.loading.JsonIcdLoader;
import com.pcapplayer.icd.model.EBitOrder;
import com.pcapplayer.icd.model.EByteOrder;
import com.pcapplayer.icd.model.ELengthUnit;
import com.pcapplayer.icd.model.FieldDef;
import com.pcapplayer.icd.model.HeaderDef;
import com.pcapplayer.icd.model.IcdDefinition;
import com.pcapplayer.icd.model.MessageDef;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Loads an ICD written in the annotation style ({@code "format": "annotations"}): the classes and
 * fields of the legacy Java annotation mechanism ({@code @Ordinal}, {@code @Bits}, {@code @Unsigned}, ...)
 * as JSON, one property per annotation. See docs/icd-annotations-json.md.
 * <p>
 * The result is the same model as the plain JSON format, checked by the same {@link IcdModelValidator}.
 */
public final class AnnotationJsonIcdLoader {

    /** The value of {@code "format"} that selects this loader. */
    public static final String FORMAT = "annotations";

    private static final Set<String> ROOT_KEYS = Set.of("$schema", "format", "id", "name", "description", "byteOrder",
            "bitOrder", "header", "messages", "classes", "enums");
    private static final Set<String> HEADER_KEYS = Set.of("class", "opcodeField", "lengthField", "lengthIncludesHeader",
            "lengthUnit", "maxMessageBytes");
    private static final Set<String> MESSAGE_KEYS = Set.of("opcode", "class", "name", "description");
    private static final Set<String> ENUM_KEYS = Set.of("description", "constants", "intValued");

    private final JsonReader reader = new JsonReader();
    private final Map<Object, String> locations = new IdentityHashMap<>();

    private AnnotationJsonIcdLoader() {
    }

    /** True if {@code root} asks for this format. */
    public static boolean accepts(JsonNode root) {
        return root != null && root.isObject() && FORMAT.equals(root.path("format").asText());
    }

    public static IcdDefinition load(JsonNode root) throws IcdLoadException {
        return new AnnotationJsonIcdLoader().loadIcd(root);
    }

    private IcdDefinition loadIcd(JsonNode root) throws IcdLoadException {
        reader.onlyKeys(root, "$", ROOT_KEYS);
        String id = reader.text(root, "id", "$", true);
        String name = reader.text(root, "name", "$", true);
        // the legacy streams are read with DataInputStream, which is big-endian
        EByteOrder byteOrder = reader.enumValue(root, "byteOrder", "$", EByteOrder.class, EByteOrder.BIG_ENDIAN);
        EBitOrder bitOrder = reader.enumValue(root, "bitOrder", "$", EBitOrder.class, EBitOrder.MSB_FIRST);
        JsonNode classNodes = root.get("classes");
        if (classNodes == null || !classNodes.isObject()) reader.problem("$.classes", "is required: an object of class name → class");

        ClassTranslator classes = new ClassTranslator(reader, classNodes, loadEnums(root.get("enums")), bitOrder, locations);
        HeaderDef header = loadHeader(root.get("header"), classes);
        List<MessageDef> messages = loadMessages(root.get("messages"), classes);
        IcdDefinition icd = new IcdDefinition(id, name, byteOrder, header, messages);
        locations.put(icd, "$");
        if (!reader.problems().isEmpty()) throw new IcdLoadException(reader.problems());

        List<IcdProblem> invalid = IcdModelValidator.validate(icd, IcdModelValidator.locator(locations, "$"));
        if (!invalid.isEmpty()) throw new IcdLoadException(invalid);
        return icd;
    }

    /** Each enum's values by number: {@code constants} are numbered 0, 1, 2 ... (Java ordinals), {@code intValued} gives them. */
    private Map<String, SortedMap<Long, String>> loadEnums(JsonNode node) {
        Map<String, SortedMap<Long, String>> enums = new HashMap<>();
        if (node == null) return enums;
        if (!reader.isObject(node, "$.enums")) return enums;

        node.fields().forEachRemaining(entry -> {
            String path = "$.enums." + entry.getKey();
            JsonNode enumNode = entry.getValue();
            if (!reader.isObject(enumNode, path)) return;

            reader.onlyKeys(enumNode, path, ENUM_KEYS);
            enums.put(entry.getKey(), enumValues(enumNode, path));
        });
        return enums;
    }

    private SortedMap<Long, String> enumValues(JsonNode enumNode, String path) {
        SortedMap<Long, String> values = new TreeMap<>();
        JsonNode constants = enumNode.get("constants");
        JsonNode intValued = enumNode.get("intValued");

        if ((constants == null) == (intValued == null)) {
            reader.problem(path, "needs exactly one of constants (names in declaration order) or intValued (name → value)");
        } else if (constants != null && constants.isArray()) {
            for (int i = 0; i < constants.size(); i++) values.put((long) i, constants.get(i).asText());
        } else if (intValued != null && intValued.isObject()) {
            intValued.fields().forEachRemaining(value -> {
                if (value.getValue().isIntegralNumber()) values.put(value.getValue().asLong(), value.getKey());
                else reader.problem(path + ".intValued." + value.getKey(), "must be an integer");
            });
        } else {
            reader.problem(path, "constants must be an array of names, intValued an object of name → value");
        }
        return values;
    }

    private HeaderDef loadHeader(JsonNode node, ClassTranslator classes) {
        if (!reader.isObject(node, "$.header")) return null;

        reader.onlyKeys(node, "$.header", HEADER_KEYS);
        String className = reader.text(node, "class", "$.header", true);
        String opcodeField = reader.text(node, "opcodeField", "$.header", true);
        String lengthField = reader.text(node, "lengthField", "$.header", true);
        boolean includesHeader = reader.flag(node, "lengthIncludesHeader", "$.header", true);
        ELengthUnit unit = reader.enumValue(node, "lengthUnit", "$.header", ELengthUnit.class, ELengthUnit.BYTES);
        Integer max = reader.integer(node, "maxMessageBytes", "$.header", false);
        List<FieldDef> fields = className == null ? List.of() : classes.fieldsOf(className, "$.header.class");

        HeaderDef header = new HeaderDef(fields, opcodeField, lengthField, includesHeader, unit,
                max == null ? HeaderDef.DEFAULT_MAX_MESSAGE_BYTES : max);
        locations.put(header, "$.header");
        return header;
    }

    private List<MessageDef> loadMessages(JsonNode node, ClassTranslator classes) {
        List<MessageDef> messages = new ArrayList<>();

        if (node == null || !node.isArray()) {
            reader.problem("$.messages", "is required: an array of {opcode, class}");
            return messages;
        }

        for (int i = 0; i < node.size(); i++) {
            String path = "$.messages[" + i + "]";
            JsonNode messageNode = node.get(i);
            if (!reader.isObject(messageNode, path)) continue;

            reader.onlyKeys(messageNode, path, MESSAGE_KEYS);
            Long opcode = opcode(messageNode, path);
            String className = reader.text(messageNode, "class", path, true);
            if (opcode == null || className == null) continue;

            String name = reader.text(messageNode, "name", path, false);
            MessageDef message = new MessageDef(opcode, name == null ? className : name, null,
                    classes.fieldsOf(className, path + ".class"));
            locations.put(message, path);
            messages.add(message);
        }
        messages.sort(Comparator.comparingLong(MessageDef::opcode));
        return messages;
    }

    private Long opcode(JsonNode messageNode, String path) {
        JsonNode node = messageNode.get("opcode");
        Long opcode = node == null ? null : JsonIcdLoader.parseNumber(node.isNumber() ? node.asText() : node.asText(""));
        if (opcode == null) reader.problem(path + ".opcode", "is required: a number, or a string like \"0x12\"");
        return opcode;
    }
}
