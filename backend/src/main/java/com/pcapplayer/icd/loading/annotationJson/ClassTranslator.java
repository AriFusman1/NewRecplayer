package com.pcapplayer.icd.loading.annotationJson;

import com.fasterxml.jackson.databind.JsonNode;
import com.pcapplayer.icd.model.ArrayDef;
import com.pcapplayer.icd.model.EBitOrder;
import com.pcapplayer.icd.model.EFieldType;
import com.pcapplayer.icd.model.EnumDef;
import com.pcapplayer.icd.model.FieldDef;
import com.pcapplayer.icd.model.StringDef;
import com.pcapplayer.icd.model.StructDef;
import com.pcapplayer.icd.model.ValueRules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;

/**
 * Turns the classes of an annotation-style ICD into model fields, following the rules of the legacy
 * annotation mechanism: fields are read by {@code ordinal}, consecutive {@code bits} share a bit chunk,
 * sizes come from the Java types (see {@link JavaTypes}), and so on (docs/icd-annotations-json.md).
 */
final class ClassTranslator {

    private static final Set<String> CLASS_KEYS = Set.of("description", "binaryData", "validated", "charset", "fields");
    private static final Set<String> CHARSET_KEYS = Set.of("value", "isReversed");
    private static final Set<String> FIELD_KEYS = Set.of("name", "type", "ordinal", "description", "unsigned", "bits", "bit",
            "size", "enumerable", "fixedPoint", "validRange", "invalidityValue", "lengthEncoded", "lengthEncoder",
            "binaryInternal", "transient", "existenceEncoded", "custom", "encoded");
    private static final Set<String> BITS_KEYS = Set.of("value", "bitfield");
    private static final Set<String> FIXED_POINT_KEYS = Set.of("lsb", "type");
    private static final Set<String> VALID_RANGE_KEYS = Set.of("minValue", "maxValue");
    private static final Set<String> LENGTH_ENCODED_KEYS = Set.of("encodingField", "value", "unsigned");
    private static final Set<String> LENGTH_ENCODER_KEYS = Set.of("isLengthInBytes");
    /** Fields with these are read by code (a method, a customizer, a codec): decoding stops before them. */
    private static final List<String> CODE_READ_KEYS = List.of("existenceEncoded", "custom", "encoded");

    /** One field as written in the JSON, with where it is. */
    private record Entry(JsonNode node, String path, int ordinal) {
    }

    /** How a class stores its strings ({@code @Charset} on the class). */
    private record Charset(String encoding, boolean reversed) {
    }

    private final JsonReader reader;
    private final JsonNode classNodes;
    private final Map<String, SortedMap<Long, String>> enums;
    private final EBitOrder bitOrder;
    private final Map<Object, String> locations;
    private final Map<String, StructDef> structs = new HashMap<>();
    private final Set<String> translating = new HashSet<>();

    ClassTranslator(JsonReader reader, JsonNode classNodes, Map<String, SortedMap<Long, String>> enums, EBitOrder bitOrder,
                    Map<Object, String> locations) {
        this.reader = reader;
        this.classNodes = classNodes;
        this.enums = enums;
        this.bitOrder = bitOrder;
        this.locations = locations;
    }

    boolean hasClass(String className) {
        return classNodes != null && classNodes.has(className);
    }

    /** The fields of {@code className}, in wire order; empty (with a problem) if it cannot be translated. */
    List<FieldDef> fieldsOf(String className, String usedAt) {
        if (!hasClass(className)) {
            reader.problem(usedAt, "class '" + className + "' is not defined under $.classes");
            return List.of();
        }
        if (!translating.add(className)) {
            reader.problem(usedAt, "class '" + className + "' contains itself");
            return List.of();
        }

        try {
            return translateClass(className);
        } finally {
            translating.remove(className);
        }
    }

    private StructDef struct(String className, String usedAt) {
        StructDef known = structs.get(className);
        if (known != null) return known;

        StructDef struct = new StructDef(className, fieldsOf(className, usedAt));
        structs.put(className, struct);
        return struct;
    }

    private List<FieldDef> translateClass(String className) {
        String path = "$.classes." + className;
        JsonNode classNode = classNodes.get(className);
        if (!reader.isObject(classNode, path)) return List.of();

        reader.onlyKeys(classNode, path, CLASS_KEYS);
        Charset charset = charset(classNode.get("charset"), path + ".charset");
        JsonNode fieldNodes = classNode.get("fields");

        if (fieldNodes == null || !fieldNodes.isArray()) {
            reader.problem(path + ".fields", "is required: the fields with their ordinals");
            return List.of();
        }
        return translateFields(entries(fieldNodes, path + ".fields"), charset);
    }

    private Charset charset(JsonNode node, String path) {
        if (node == null) return new Charset(StringDef.DEFAULT_ENCODING, false);
        if (!reader.isObject(node, path)) return new Charset(StringDef.DEFAULT_ENCODING, false);

        reader.onlyKeys(node, path, CHARSET_KEYS);
        String encoding = reader.text(node, "value", path, true);
        return new Charset(encoding == null ? StringDef.DEFAULT_ENCODING : encoding, reader.flag(node, "isReversed", path, false));
    }

    /** The fields that are on the wire (not transient), sorted by ordinal. */
    private List<Entry> entries(JsonNode fieldNodes, String path) {
        List<Entry> entries = new ArrayList<>();
        Set<Integer> ordinals = new HashSet<>();

        for (int i = 0; i < fieldNodes.size(); i++) {
            JsonNode node = fieldNodes.get(i);
            String fieldPath = path + "[" + i + "]";
            if (!reader.isObject(node, fieldPath)) continue;

            reader.onlyKeys(node, fieldPath, FIELD_KEYS);
            if (reader.flag(node, "transient", fieldPath, false)) continue; // @Transient: not on the wire
            Integer ordinal = reader.integer(node, "ordinal", fieldPath, true);
            if (ordinal == null) continue;
            if (!ordinals.add(ordinal)) reader.problem(fieldPath + ".ordinal", "duplicate ordinal " + ordinal);
            entries.add(new Entry(node, fieldPath, ordinal));
        }
        entries.sort(Comparator.comparingInt(Entry::ordinal));
        return entries;
    }

    private List<FieldDef> translateFields(List<Entry> entries, Charset charset) {
        List<FieldDef> fields = new ArrayList<>();
        Map<String, Boolean> lengthInBytes = new HashMap<>(); // @LenghtEncoder fields: name → isLengthInBytes
        BitGroup group = null;

        for (Entry entry : entries) {
            if (readByCode(entry)) break; // nothing after it can be located without running that code

            if (entry.node().has("bits") || entry.node().has("bit")) {
                group = addBits(entry, group, fields);
                continue;
            }
            closeGroup(group, fields);
            group = null;
            FieldDef field = translateField(entry, charset, fields, lengthInBytes);
            if (field != null) add(fields, field, entry.path());
        }
        closeGroup(group, fields);
        return fields;
    }

    private boolean readByCode(Entry entry) {
        return CODE_READ_KEYS.stream().anyMatch(key -> entry.node().has(key));
    }

    private void add(List<FieldDef> fields, FieldDef field, String path) {
        fields.add(field);
        locations.put(field, path);
    }

    // ------------------------------------------------------------------ @Bits and @Bit

    private BitGroup addBits(Entry entry, BitGroup group, List<FieldDef> fields) {
        JsonNode node = entry.node();
        String name = reader.text(node, "name", entry.path(), true);
        if (name == null) return group;

        if (node.has("bit")) { // @Bit: one bit of the chunk the fields before it opened
            if (group == null) {
                reader.problem(entry.path() + ".bit", "a bit must follow bits fields whose chunk is not full yet");
                return null;
            }
            return addTo(group, name, 1, entry, fields);
        }
        JsonNode bits = node.get("bits");
        if (!reader.isObject(bits, entry.path() + ".bits")) return group;

        reader.onlyKeys(bits, entry.path() + ".bits", BITS_KEYS);
        Integer width = reader.integer(bits, "value", entry.path() + ".bits", true);
        EBitfieldLength length = reader.enumValue(bits, "bitfield", entry.path() + ".bits", EBitfieldLength.class, null);
        if (!bits.has("bitfield")) reader.problem(entry.path() + ".bits.bitfield", "is required (BITS8, BITS16, BITS24 or BITS32)");
        if (width == null || length == null) return group;

        if (group != null && group.length() != length) {
            reader.problem(entry.path() + ".bits.bitfield", "the chunk opened at " + group.path() + " is " + group.length()
                    + " and has only " + group.used() + " of its bits; a new " + length + " cannot start before it is full");
            return group;
        }
        return addTo(group == null ? new BitGroup(length, entry.path()) : group, name, width, entry, fields);
    }

    /** Adds the bits, and closes the group once its chunk is full. */
    private BitGroup addTo(BitGroup group, String name, int width, Entry entry, List<FieldDef> fields) {
        if (width < 1 || !group.add(name, width)) {
            reader.problem(entry.path(), width + " bits do not fit: the " + group.length() + " chunk opened at " + group.path()
                    + " has " + (group.length().bits - group.used()) + " bits left");
            return group;
        }
        if (!group.full()) return group;

        add(fields, group.toField(fields.size() + 1, bitOrder), group.path());
        return null;
    }

    private void closeGroup(BitGroup group, List<FieldDef> fields) {
        if (group == null) return;

        reader.problem(group.path(), "the bits fields from here add up to " + group.used() + " bits, but their chunk is "
                + group.length() + "; the values of consecutive bits must add up to the bitfield length");
    }

    // ------------------------------------------------------------------ one field

    private FieldDef translateField(Entry entry, Charset charset, List<FieldDef> earlier, Map<String, Boolean> lengthInBytes) {
        JsonNode node = entry.node();
        String path = entry.path();
        String name = reader.text(node, "name", path, true);
        String type = reader.text(node, "type", path, true);
        if (name == null || type == null) return null;

        boolean unsigned = reader.flag(node, "unsigned", path, false);
        recordLengthEncoder(node, path, name, lengthInBytes);

        if (!type.endsWith("[]")) {
            FieldDef field = element(name, earlier.size() + 1, type, node, path, unsigned, charset);
            return field == null ? null : withRules(field, node, path);
        }
        ArrayDef array = array(name, node, path, earlier, lengthInBytes); // may add a length field before the array
        String elementType = type.substring(0, type.length() - 2);
        FieldDef element = element(name, earlier.size() + 1, elementType, node, path, unsigned, charset);
        return array == null || element == null ? null : withRules(element, node, path).withArray(array);
    }

    private void recordLengthEncoder(JsonNode node, String path, String name, Map<String, Boolean> lengthInBytes) {
        JsonNode encoder = node.get("lengthEncoder");
        if (encoder == null || !reader.isObject(encoder, path + ".lengthEncoder")) return;

        reader.onlyKeys(encoder, path + ".lengthEncoder", LENGTH_ENCODER_KEYS);
        lengthInBytes.put(name, reader.flag(encoder, "isLengthInBytes", path + ".lengthEncoder", false));
    }

    /** One element: a number, boolean, string, enum or nested class. */
    private FieldDef element(String name, int order, String javaType, JsonNode node, String path, boolean unsigned, Charset charset) {
        if ("String".equals(javaType)) return string(name, order, node, path, charset);
        if (JavaTypes.isBoolean(javaType)) return FieldDef.primitive(name, order, EFieldType.BOOL);
        if (JavaTypes.isNumber(javaType)) return number(name, order, javaType, node, path, unsigned);
        if (enums.containsKey(javaType)) return enumField(name, order, javaType, node, path, unsigned);
        if (hasClass(javaType)) return FieldDef.ofStruct(name, order, struct(javaType, path + ".type"));

        reader.problem(path + ".type", "'" + javaType + "' is not a Java number, boolean, String, or an enum under $.enums"
                + " or a class under $.classes");
        return null;
    }

    private FieldDef string(String name, int order, JsonNode node, String path, Charset charset) {
        Integer size = reader.integer(node, "size", path, false);

        if (size == null) {
            reader.problem(path + ".size", "a String needs its fixed length (@Size)");
            return null;
        }
        return FieldDef.ofString(name, order, new StringDef(size, charset.encoding(), charset.reversed()));
    }

    /** A number is read at its Java size, or at the size {@code fixedPoint.type} gives. */
    private FieldDef number(String name, int order, String javaType, JsonNode node, String path, boolean unsigned) {
        JsonNode fixedPoint = node.get("fixedPoint");

        if (fixedPoint == null) return FieldDef.primitive(name, order, JavaTypes.ofField(javaType, unsigned));
        if (!reader.isObject(fixedPoint, path + ".fixedPoint")) return null;

        reader.onlyKeys(fixedPoint, path + ".fixedPoint", FIXED_POINT_KEYS);
        String sizeClass = reader.text(fixedPoint, "type", path + ".fixedPoint", true);
        reader.number(fixedPoint, "lsb", path + ".fixedPoint");
        if (!fixedPoint.has("lsb")) reader.problem(path + ".fixedPoint.lsb", "is required");
        if (sizeClass == null) return null;

        EFieldType type = JavaTypes.ofSizeClass(sizeClass, unsigned);

        if (type == null) {
            reader.problem(path + ".fixedPoint.type", "must be byte, short, int or long (got \"" + sizeClass + "\")");
            return null;
        }
        return FieldDef.primitive(name, order, type);
    }

    /** An enum is read at the size {@code enumerable} gives; its values come from $.enums. */
    private FieldDef enumField(String name, int order, String enumName, JsonNode node, String path, boolean unsigned) {
        String sizeClass = reader.text(node, "enumerable", path, false);

        if (sizeClass == null) {
            reader.problem(path + ".enumerable", "an enum field needs the size it is read as, e.g. \"byte\" (@Enumerable(byte.class))");
            return null;
        }
        EFieldType type = JavaTypes.ofSizeClass(sizeClass, unsigned);

        if (type == null) {
            reader.problem(path + ".enumerable", "must be byte, short, int or long (got \"" + sizeClass + "\")");
            return null;
        }
        return FieldDef.ofEnum(name, order, new EnumDef(enumName, type, enums.get(enumName)));
    }

    private FieldDef withRules(FieldDef field, JsonNode node, String path) {
        Double lsb = node.has("fixedPoint") && node.get("fixedPoint").isObject()
                ? reader.number(node.get("fixedPoint"), "lsb", path + ".fixedPoint") : null;
        Double minValue = null;
        Double maxValue = null;
        JsonNode range = node.get("validRange");

        if (range != null && reader.isObject(range, path + ".validRange")) {
            reader.onlyKeys(range, path + ".validRange", VALID_RANGE_KEYS);
            minValue = reader.number(range, "minValue", path + ".validRange");
            maxValue = reader.number(range, "maxValue", path + ".validRange");
        }
        ValueRules rules = new ValueRules(lsb, minValue, maxValue, reader.longValue(node, "invalidityValue", path));
        return rules.isEmpty() ? field : field.withRules(rules);
    }

    // ------------------------------------------------------------------ arrays

    /**
     * A fixed array has {@code size}. A variable one has {@code lengthEncoded}: its length is in the field
     * {@code encodingField} names, or (no encodingField) in an integer of {@code value} bits just before
     * the array, or (neither) in the field just before it.
     */
    private ArrayDef array(String name, JsonNode node, String path, List<FieldDef> earlier, Map<String, Boolean> lengthInBytes) {
        Integer size = reader.integer(node, "size", path, false);
        JsonNode encoded = node.get("lengthEncoded");

        if ((size == null) == (encoded == null)) {
            reader.problem(path, "an array needs exactly one of size (@Size) or lengthEncoded (@LengthEncoded)");
            return null;
        }
        if (size != null) return ArrayDef.fixed(size);
        if (!reader.isObject(encoded, path + ".lengthEncoded")) return null;

        reader.onlyKeys(encoded, path + ".lengthEncoded", LENGTH_ENCODED_KEYS);
        String encodingField = reader.text(encoded, "encodingField", path + ".lengthEncoded", false);
        Integer prefixBits = reader.integer(encoded, "value", path + ".lengthEncoded", false);

        if (encodingField == null && prefixBits != null) {
            encodingField = addLengthPrefix(name, prefixBits, reader.flag(encoded, "unsigned", path + ".lengthEncoded", false),
                    earlier, path);
            if (encodingField == null) return null;
        } else if (encodingField == null) {
            if (earlier.isEmpty()) {
                reader.problem(path + ".lengthEncoded", "no encodingField and no field before the array to hold its length");
                return null;
            }
            encodingField = earlier.get(earlier.size() - 1).name();
        }
        boolean inBytes = lengthInBytes.getOrDefault(encodingField.trim(), false);
        return new ArrayDef(0, encodingField.trim(), inBytes);
    }

    /** The length written right before the array: a field of its own named {@code <array>Length}. */
    private String addLengthPrefix(String arrayName, int bits, boolean unsigned, List<FieldDef> earlier, String path) {
        EFieldType type = JavaTypes.ofBits(bits, unsigned);

        if (type == null) {
            reader.problem(path + ".lengthEncoded.value", "must be 8, 16 or 32 bits (24 only unsigned), got " + bits);
            return null;
        }
        String name = arrayName + "Length";
        add(earlier, FieldDef.primitive(name, earlier.size() + 1, type), path + ".lengthEncoded");
        return name;
    }
}
