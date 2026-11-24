package com.pcapplayer.icd.loading;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.pcapplayer.common.Json;
import com.pcapplayer.icd.model.ArrayDef;
import com.pcapplayer.icd.model.BitDef;
import com.pcapplayer.icd.model.BitfieldDef;
import com.pcapplayer.icd.model.EBitOrder;
import com.pcapplayer.icd.model.EByteOrder;
import com.pcapplayer.icd.model.EFieldType;
import com.pcapplayer.icd.model.ELengthUnit;
import com.pcapplayer.icd.model.EnumDef;
import com.pcapplayer.icd.model.FieldDef;
import com.pcapplayer.icd.model.HeaderDef;
import com.pcapplayer.icd.model.IcdDefinition;
import com.pcapplayer.icd.model.MessageDef;
import com.pcapplayer.icd.model.StringDef;
import com.pcapplayer.icd.model.StructDef;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds an {@link IcdDefinition} from the JSON format described in {@code docs/icd-json-format.md}
 * (schema: {@code icd/icd.schema.json}). Every problem is reported with its JSON path, and all problems
 * are reported together.
 * <p>
 * The loader first checks the shape of the JSON (required properties, value types, unknown
 * properties), then builds the model and runs the same {@link IcdModelValidator} as the annotation loader.
 */
public final class JsonIcdLoader {

    private static final Set<String> ROOT_KEYS = Set.of("$schema", "id", "name", "description", "byteOrder", "header",
            "enums", "structs", "messages");
    private static final Set<String> HEADER_KEYS = Set.of("lengthIncludesHeader", "lengthUnit", "maxMessageBytes", "fields");
    private static final Set<String> MESSAGE_KEYS = Set.of("opcode", "name", "description", "byteOrder", "fields");
    private static final Set<String> STRUCT_KEYS = Set.of("description", "fields");
    private static final Set<String> ENUM_KEYS = Set.of("description", "type", "values");
    private static final Set<String> FIELD_KEYS = Set.of("name", "order", "description", "byteOrder", "array", "role",
            "type", "enum", "struct", "string", "bitfield", "padding");
    private static final List<String> KIND_KEYS = List.of("type", "enum", "struct", "string", "bitfield", "padding");
    private static final Set<String> ARRAY_KEYS = Set.of("length", "lengthField");
    private static final Set<String> STRING_KEYS = Set.of("length", "encoding");
    private static final Set<String> BITFIELD_KEYS = Set.of("type", "bitOrder", "bits");
    private static final Set<String> BIT_KEYS = Set.of("name", "bits", "description");

    private final List<IcdProblem> problems = new ArrayList<>();
    private final Map<Object, String> locations = new IdentityHashMap<>();
    private final Map<String, EnumDef> enums = new HashMap<>();
    private final Map<String, StructDef> structs = new HashMap<>();
    private final Set<String> resolving = new HashSet<>();
    private JsonNode structNodes;

    private JsonIcdLoader() {
    }

    public static IcdDefinition load(byte[] json) throws IcdLoadException {
        JsonNode root;

        try {
            root = Json.MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IcdLoadException(List.of(new IcdProblem("$", "not valid JSON: " + e.getOriginalMessage()
                    + (e.getLocation() == null ? "" : " (line " + e.getLocation().getLineNr()
                    + ", column " + e.getLocation().getColumnNr() + ")"))));
        } catch (IOException e) {
            throw new IcdLoadException(List.of(new IcdProblem("$", "cannot read: " + e.getMessage())));
        }
        return new JsonIcdLoader().loadIcd(root);
    }

    private IcdDefinition loadIcd(JsonNode root) throws IcdLoadException {
        if (root == null || !root.isObject()) throw new IcdLoadException(List.of(new IcdProblem("$", "must be a JSON object")));

        onlyKeys(root, "$", ROOT_KEYS);
        String id = text(root, "id", "$", true);
        String name = text(root, "name", "$", true);
        EByteOrder byteOrder = enumValue(root, "byteOrder", "$", EByteOrder.class, EByteOrder.BIG_ENDIAN);
        loadEnums(root.get("enums"));
        structNodes = root.get("structs");
        if (structNodes != null && !structNodes.isObject()) problem("$.structs", "must be an object of name → struct");
        HeaderDef header = loadHeader(root.get("header"));
        List<MessageDef> messages = loadMessages(root.get("messages"));
        IcdDefinition icd = new IcdDefinition(id, name, byteOrder, header, messages);
        locations.put(icd, "$");
        if (!problems.isEmpty()) throw new IcdLoadException(problems);

        List<IcdProblem> invalid = IcdModelValidator.validate(icd, IcdModelValidator.locator(locations, "$"));
        if (!invalid.isEmpty()) throw new IcdLoadException(invalid);
        return icd;
    }

    // ------------------------------------------------------------------ sections

    private void loadEnums(JsonNode node) {
        if (node == null) return;

        if (!node.isObject()) {
            problem("$.enums", "must be an object of name → enum");
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> entries = node.fields();

        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            String path = "$.enums." + entry.getKey();
            JsonNode enumNode = entry.getValue();
            if (!isObject(enumNode, path)) continue;

            onlyKeys(enumNode, path, ENUM_KEYS);
            EFieldType type = enumValue(enumNode, "type", path, EFieldType.class, null);
            if (!enumNode.has("type")) problem(path + ".type", "is required");
            TreeMap<Long, String> values = new TreeMap<>();
            JsonNode valuesNode = enumNode.get("values");

            if (valuesNode == null || !valuesNode.isObject()) {
                problem(path + ".values", "is required: an object of value → name, e.g. {\"0\": \"OFF\", \"1\": \"ON\"}");
            } else {
                valuesNode.fields().forEachRemaining(value -> {
                    Long number = parseNumber(value.getKey());
                    if (number == null) problem(path + ".values." + value.getKey(), "key must be an integer (decimal or 0x hex)");
                    else if (!value.getValue().isTextual()) problem(path + ".values." + value.getKey(), "must be a name (string)");
                    else values.put(number, value.getValue().asText());
                });
            }
            enums.put(entry.getKey(), new EnumDef(entry.getKey(), type, values));
        }
    }

    private HeaderDef loadHeader(JsonNode node) {
        String path = "$.header";

        if (node == null || !node.isObject()) {
            problem(path, "is required: an object with the header fields");
            return null;
        }
        onlyKeys(node, path, HEADER_KEYS);
        boolean includesHeader = bool(node, "lengthIncludesHeader", path, true);
        ELengthUnit unit = enumValue(node, "lengthUnit", path, ELengthUnit.class, ELengthUnit.BYTES);
        int maxBytes = integer(node, "maxMessageBytes", path, HeaderDef.DEFAULT_MAX_MESSAGE_BYTES);
        List<FieldDef> fields = loadFields(node.get("fields"), path + ".fields", true);
        String opcode = roleField(node.get("fields"), path + ".fields", "opcode");
        String length = roleField(node.get("fields"), path + ".fields", "length");
        HeaderDef header = new HeaderDef(fields, opcode, length, includesHeader, unit, maxBytes);
        locations.put(header, path);
        return header;
    }

    /** Name of the header field with {@code "role": role}; null if none (the validator reports it). */
    private String roleField(JsonNode fields, String path, String role) {
        if (fields == null || !fields.isArray()) return null;
        String found = null;

        for (int i = 0; i < fields.size(); i++) {
            JsonNode field = fields.get(i);

            if (field.isObject() && role.equals(field.path("role").asText(null))) {
                if (found != null) problem(path + "[" + i + "].role", "only one field can have role '" + role + "'");
                else found = field.path("name").asText(null);
            }
        }
        return found;
    }

    private List<MessageDef> loadMessages(JsonNode node) {
        List<MessageDef> messages = new ArrayList<>();

        if (node == null || !node.isArray()) {
            problem("$.messages", "is required: an array of messages");
            return messages;
        }

        for (int i = 0; i < node.size(); i++) {
            String path = "$.messages[" + i + "]";
            JsonNode messageNode = node.get(i);
            if (!isObject(messageNode, path)) continue;

            onlyKeys(messageNode, path, MESSAGE_KEYS);
            Long opcode = number(messageNode, "opcode", path);
            String name = text(messageNode, "name", path, true);
            EByteOrder byteOrder = enumValue(messageNode, "byteOrder", path, EByteOrder.class, null);
            List<FieldDef> fields = loadFields(messageNode.get("fields"), path + ".fields", false);
            MessageDef message = new MessageDef(opcode == null ? -1 : opcode, name, byteOrder, fields);
            locations.put(message, path);
            messages.add(message);
        }
        messages.sort(Comparator.comparingLong(MessageDef::opcode));
        return messages;
    }

    private StructDef struct(String name, String usedAt) {
        StructDef known = structs.get(name);
        if (known != null) return known;

        JsonNode node = structNodes == null || !structNodes.isObject() ? null : structNodes.get(name);

        if (node == null) {
            problem(usedAt, "unknown struct '" + name + "' (define it under $.structs)");
            return null;
        }
        String path = "$.structs." + name;

        if (!resolving.add(name)) {
            problem(path, "struct '" + name + "' contains itself");
            return null;
        }

        try {
            if (!isObject(node, path)) return null;
            onlyKeys(node, path, STRUCT_KEYS);
            StructDef struct = new StructDef(name, loadFields(node.get("fields"), path + ".fields", false));
            structs.put(name, struct);
            return struct;
        } finally {
            resolving.remove(name);
        }
    }

    // ------------------------------------------------------------------ fields

    private List<FieldDef> loadFields(JsonNode node, String path, boolean header) {
        List<FieldDef> fields = new ArrayList<>();

        if (node == null || !node.isArray()) {
            problem(path, "is required: an array of fields");
            return fields;
        }

        for (int i = 0; i < node.size(); i++) {
            FieldDef field = loadField(node.get(i), path + "[" + i + "]", i + 1, header);
            if (field != null) fields.add(field);
        }
        fields.sort(Comparator.comparingInt(FieldDef::order)); // stable: equal orders keep their place (and are reported)
        return fields;
    }

    /** @param defaultOrder used when the field has no "order": its 1-based position in the list */
    private FieldDef loadField(JsonNode node, String path, int defaultOrder, boolean header) {
        if (!isObject(node, path)) return null;
        onlyKeys(node, path, FIELD_KEYS);
        String name = text(node, "name", path, true);
        int order = integer(node, "order", path, defaultOrder);
        if (node.has("role") && !header) problem(path + ".role", "only header fields can have a role");

        if (node.has("role") && !List.of("opcode", "length").contains(node.get("role").asText())) {
            problem(path + ".role", "must be \"opcode\" or \"length\"");
        }

        List<String> kinds = KIND_KEYS.stream().filter(node::has).toList();

        if (kinds.size() != 1) {
            problem(path, kinds.isEmpty()
                    ? "needs one of " + String.join(", ", KIND_KEYS)
                    : "has more than one of " + String.join(", ", kinds) + "; give exactly one");
            return null;
        }
        FieldDef field = elementField(node, path, name, order, kinds.get(0));
        if (field == null) return null;

        field = field.withByteOrder(enumValue(node, "byteOrder", path, EByteOrder.class, null));
        if (node.has("array")) field = field.withArray(array(node.get("array"), path + ".array"));
        locations.put(field, path);
        return field;
    }

    private FieldDef elementField(JsonNode node, String path, String name, int order, String kind) {
        return switch (kind) {
            case "type" -> {
                EFieldType type = enumValue(node, "type", path, EFieldType.class, null);
                yield type == null ? null : FieldDef.primitive(name, order, type);
            }

            case "enum" -> {
                String enumName = text(node, "enum", path, true);
                EnumDef enumDef = enums.get(enumName);
                if (enumDef == null && enumName != null) problem(path + ".enum", "unknown enum '" + enumName + "' (define it under $.enums)");
                yield enumDef == null ? null : FieldDef.ofEnum(name, order, enumDef);
            }

            case "struct" -> {
                String structName = text(node, "struct", path, true);
                StructDef struct = structName == null ? null : struct(structName, path + ".struct");
                yield struct == null ? null : FieldDef.ofStruct(name, order, struct);
            }
            case "string" -> string(node.get("string"), path + ".string", name, order);
            case "bitfield" -> bitfield(node.get("bitfield"), path + ".bitfield", name, order);

            default -> {
                JsonNode padding = node.get("padding");

                if (!padding.canConvertToInt()) {
                    problem(path + ".padding", "must be a number of bytes");
                    yield null;
                }
                yield FieldDef.padding(name, order, padding.asInt());
            }
        };
    }

    private FieldDef string(JsonNode node, String path, String name, int order) {
        if (!isObject(node, path)) return null;
        onlyKeys(node, path, STRING_KEYS);
        Integer length = requiredInt(node, "length", path);
        String encoding = text(node, "encoding", path, false);
        if (length == null) return null;
        return FieldDef.ofString(name, order, new StringDef(length, encoding == null ? StringDef.DEFAULT_ENCODING : encoding));
    }

    private FieldDef bitfield(JsonNode node, String path, String name, int order) {
        if (!isObject(node, path)) return null;
        onlyKeys(node, path, BITFIELD_KEYS);
        EFieldType type = enumValue(node, "type", path, EFieldType.class, null);
        if (type == null && !node.has("type")) problem(path + ".type", "is required");
        EBitOrder bitOrder = enumValue(node, "bitOrder", path, EBitOrder.class, EBitOrder.MSB_FIRST);
        JsonNode bitsNode = node.get("bits");
        List<BitDef> bits = new ArrayList<>();

        if (bitsNode == null || !bitsNode.isArray()) {
            problem(path + ".bits", "is required: an array of {\"name\", \"bits\"}");
        } else {
            for (int i = 0; i < bitsNode.size(); i++) {
                String bitPath = path + ".bits[" + i + "]";
                JsonNode bitNode = bitsNode.get(i);
                if (!isObject(bitNode, bitPath)) continue;
                onlyKeys(bitNode, bitPath, BIT_KEYS);
                String bitName = text(bitNode, "name", bitPath, true);
                Integer width = requiredInt(bitNode, "bits", bitPath);
                if (width != null) bits.add(new BitDef(bitName, width));
            }
        }
        return FieldDef.ofBitfield(name, order, new BitfieldDef(type, bitOrder, bits));
    }

    private ArrayDef array(JsonNode node, String path) {
        if (!isObject(node, path)) return null;
        onlyKeys(node, path, ARRAY_KEYS);
        int length = integer(node, "length", path, 0);
        String lengthField = text(node, "lengthField", path, false);
        return new ArrayDef(length, lengthField);
    }

    // ------------------------------------------------------------------ value helpers

    private void problem(String path, String message) {
        problems.add(new IcdProblem(path, message));
    }

    private boolean isObject(JsonNode node, String path) {
        if (node != null && node.isObject()) return true;
        problem(path, "must be an object");
        return false;
    }

    private void onlyKeys(JsonNode node, String path, Set<String> allowed) {
        node.fieldNames().forEachRemaining(key -> {
            if (!allowed.contains(key)) problem(path + "." + key, "unknown property");
        });
    }

    private String text(JsonNode node, String key, String path, boolean required) {
        JsonNode value = node.get(key);

        if (value == null || value.isNull()) {
            if (required) problem(path + "." + key, "is required");
            return null;
        }

        if (!value.isTextual()) {
            problem(path + "." + key, "must be a string");
            return null;
        }
        return value.asText();
    }

    private boolean bool(JsonNode node, String key, String path, boolean fallback) {
        JsonNode value = node.get(key);
        if (value == null) return fallback;
        if (value.isBoolean()) return value.asBoolean();
        problem(path + "." + key, "must be true or false");
        return fallback;
    }

    private int integer(JsonNode node, String key, String path, int fallback) {
        JsonNode value = node.get(key);
        if (value == null) return fallback;
        if (value.isIntegralNumber() && value.canConvertToInt()) return value.asInt();
        problem(path + "." + key, "must be an integer");
        return fallback;
    }

    private Integer requiredInt(JsonNode node, String key, String path) {
        if (!node.has(key)) {
            problem(path + "." + key, "is required");
            return null;
        }
        int value = integer(node, key, path, Integer.MIN_VALUE);
        return value == Integer.MIN_VALUE ? null : value;
    }

    /** A required integer given as a JSON number or as a string ("16", "0x10"). */
    private Long number(JsonNode node, String key, String path) {
        JsonNode value = node.get(key);

        if (value == null) {
            problem(path + "." + key, "is required");
            return null;
        }
        Long number = value.isIntegralNumber() ? Long.valueOf(value.bigIntegerValue().longValue())
                : value.isTextual() ? parseNumber(value.asText()) : null;
        if (number == null) problem(path + "." + key, "must be an integer (a number, or a string like \"0x10\")");
        return number;
    }

    /**
     * Decimal or 0x-hex, possibly negative. Values up to 2^64 − 1 are accepted for UINT64 and kept as
     * the raw (two's complement) long.
     */
    static Long parseNumber(String text) {
        String trimmed = text.trim();
        boolean negative = trimmed.startsWith("-");
        String digits = negative ? trimmed.substring(1) : trimmed;
        int radix = 10;

        if (digits.startsWith("0x") || digits.startsWith("0X")) {
            digits = digits.substring(2);
            radix = 16;
        }

        try {
            BigInteger value = new BigInteger(digits, radix);
            if (negative) value = value.negate();
            if (value.bitLength() > 64 || (negative && value.bitLength() > 63)) return null;
            return value.longValue();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private <E extends Enum<E>> E enumValue(JsonNode node, String key, String path, Class<E> type, E fallback) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) return fallback;

        if (value.isTextual()) {
            for (E constant : type.getEnumConstants()) {
                if (constant.name().equals(value.asText())) return constant;
            }
        }
        List<String> names = new ArrayList<>();
        for (E constant : type.getEnumConstants()) names.add(constant.name());
        problem(path + "." + key, "must be one of " + String.join(", ", names) + " (got " + value + ")");
        return fallback;
    }
}
