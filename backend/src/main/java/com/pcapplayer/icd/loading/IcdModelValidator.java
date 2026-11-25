package com.pcapplayer.icd.loading;

import com.pcapplayer.icd.model.ArrayDef;
import com.pcapplayer.icd.model.BitDef;
import com.pcapplayer.icd.model.EFieldKind;
import com.pcapplayer.icd.model.EFieldType;
import com.pcapplayer.icd.model.FieldDef;
import com.pcapplayer.icd.model.HeaderDef;
import com.pcapplayer.icd.model.IcdDefinition;
import com.pcapplayer.icd.model.MessageDef;
import com.pcapplayer.icd.model.StructDef;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The rules every ICD must follow, whatever it was loaded from. Both loaders build the model and then
 * run this; problems are reported where the loader says the object came from ({@code locate}).
 */
public final class IcdModelValidator {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,100}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final String HEADER_PREFIX = "header.";

    /** What a {@code lengthField} path can refer to: the header and the message's top-level fields. */
    private record Scope(List<FieldDef> headerFields, List<FieldDef> rootFields) {
    }

    private final Function<Object, String> locate;
    private final Set<IcdProblem> problems = new LinkedHashSet<>(); // a shared struct is reported once
    private final Set<StructDef> visiting = Collections.newSetFromMap(new IdentityHashMap<>());

    private IcdModelValidator(Function<Object, String> locate) {
        this.locate = locate;
    }

    /**
     * @param locate where a model object (the ICD, header, a message or a field) came from, for messages
     * @return every problem found; empty if the ICD is valid
     */
    public static List<IcdProblem> validate(IcdDefinition icd, Function<Object, String> locate) {
        IcdModelValidator validator = new IcdModelValidator(locate);
        validator.checkIcd(icd);
        return new ArrayList<>(validator.problems);
    }

    /** Locations for objects the loader has no entry for fall back to {@code fallback}. */
    public static Function<Object, String> locator(Map<Object, String> locations, String fallback) {
        return object -> locations.getOrDefault(object, fallback);
    }

    private void problem(Object where, String message) {
        problems.add(new IcdProblem(locate.apply(where), message));
    }

    private void checkIcd(IcdDefinition icd) {
        if (icd.id() == null || !ID.matcher(icd.id()).matches()) {
            problem(icd, "id must be 1-100 letters, digits, '.', '_' or '-' (got " + icd.id() + ")");
        }
        if (icd.name() == null || icd.name().isBlank()) problem(icd, "name is required");
        if (icd.byteOrder() == null) problem(icd, "byteOrder is required");

        if (icd.header() == null) {
            problem(icd, "header is required");
            return;
        }
        checkHeader(icd.header());
        if (icd.messages().isEmpty()) problem(icd, "at least one message is required");
        checkMessages(icd);
    }

    private void checkHeader(HeaderDef header) {
        if (header.fields().isEmpty()) {
            problem(header, "the header needs at least the opcode and length fields");
            return;
        }
        checkFields(header.fields(), new Scope(header.fields(), header.fields()));
        if (header.size() == FieldDef.VARIABLE) problem(header, "the header must have a fixed size (no arrays with lengthField)");
        checkRoleField(header, header.opcodeField(), "opcode");
        checkRoleField(header, header.lengthField(), "length");
        if (header.lengthUnit() == null) problem(header, "lengthUnit is required");

        if (header.size() != FieldDef.VARIABLE && header.maxMessageBytes() <= header.size()) {
            problem(header, "maxMessageBytes must be larger than the header (" + header.size() + " bytes)");
        }
    }

    private void checkRoleField(HeaderDef header, String name, String role) {
        if (name == null) {
            problem(header, "one header field must be marked as the " + role + " field");
            return;
        }
        FieldDef field = byName(header.fields(), name);

        if (field == null || field.kind() != EFieldKind.PRIMITIVE || !field.type().integer() || field.array() != null) {
            problem(header, "the " + role + " field '" + name + "' must be a top-level integer field of the header");
        }
    }

    private void checkMessages(IcdDefinition icd) {
        Set<Long> opcodes = new HashSet<>();
        Set<String> names = new HashSet<>();
        FieldDef opcodeField = byName(icd.header().fields(), icd.header().opcodeField());
        EFieldType opcodeType = opcodeField == null ? null : opcodeField.type();

        for (MessageDef message : icd.messages()) {
            if (message.name() == null || message.name().isBlank()) problem(message, "message name is required");
            else if (!names.add(message.name())) problem(message, "duplicate message name '" + message.name() + "'");
            if (!opcodes.add(message.opcode())) problem(message, "duplicate opcode " + message.opcode());

            if (opcodeType != null && opcodeType.integer() && !fits(message.opcode(), opcodeType)) {
                problem(message, "opcode " + message.opcode() + " does not fit the opcode field type " + opcodeType);
            }
            checkFields(message.fields(), new Scope(icd.header().fields(), message.fields()));
        }
    }

    private void checkFields(List<FieldDef> fields, Scope scope) {
        Set<String> names = new HashSet<>();
        Set<Integer> orders = new HashSet<>();

        for (int i = 0; i < fields.size(); i++) {
            FieldDef field = fields.get(i);

            if (field.name() == null || !NAME.matcher(field.name()).matches()) {
                problem(field, "field name must be letters, digits or '_' and not start with a digit (got " + field.name() + ")");
            } else if (!names.add(field.name())) {
                problem(field, "duplicate field name '" + field.name() + "'");
            }
            if (!orders.add(field.order())) problem(field, "duplicate order " + field.order());

            if (field.kind() == null) {
                problem(field, "the field's type is missing");
                continue;
            }
            checkKind(field, scope);
            if (field.array() != null) checkArray(field, fields.subList(0, i), scope);
        }
    }

    private void checkKind(FieldDef field, Scope scope) {
        switch (field.kind()) {
            case PRIMITIVE -> {
                if (field.type() == null) problem(field, "type is required");
            }
            case ENUM -> checkEnum(field);
            case STRING -> checkString(field);
            case BITFIELD -> checkBitfield(field);

            case PADDING -> {
                if (field.paddingBytes() <= 0) problem(field, "padding must be at least 1 byte");
                if (field.array() != null) problem(field, "padding cannot be an array; give the total bytes instead");
            }
            case STRUCT -> checkStruct(field, scope);
        }
    }

    private void checkEnum(FieldDef field) {
        if (field.enumDef().type() == null || !field.enumDef().type().integer()) {
            problem(field, "enum '" + field.enumDef().name() + "' must have an integer type");
            return;
        }
        if (field.enumDef().values().isEmpty()) problem(field, "enum '" + field.enumDef().name() + "' has no values");

        for (Map.Entry<Long, String> value : field.enumDef().values().entrySet()) {
            if (!fits(value.getKey(), field.enumDef().type())) {
                problem(field, "enum '" + field.enumDef().name() + "' value " + value.getKey() + " does not fit " + field.enumDef().type());
            }
        }
    }

    private void checkString(FieldDef field) {
        if (field.string().length() <= 0) problem(field, "string length must be at least 1");

        try {
            if (!Charset.isSupported(field.string().encoding())) problem(field, "unknown encoding '" + field.string().encoding() + "'");
        } catch (IllegalArgumentException e) {
            problem(field, "invalid encoding '" + field.string().encoding() + "'");
        }
    }

    private void checkBitfield(FieldDef field) {
        EFieldType container = field.bitfield().type();
        if (container == null || !container.integer()) problem(field, "a bitfield's type must be an integer type");
        if (field.bitfield().bitOrder() == null) problem(field, "bitOrder is required");
        if (field.bitfield().bits().isEmpty()) problem(field, "a bitfield needs at least one bit field");
        Set<String> names = new HashSet<>();
        int total = 0;

        for (BitDef bit : field.bitfield().bits()) {
            if (bit.name() == null || !NAME.matcher(bit.name()).matches()) problem(field, "invalid bit field name '" + bit.name() + "'");
            else if (!names.add(bit.name())) problem(field, "duplicate bit field name '" + bit.name() + "'");
            if (bit.bits() < 1) problem(field, "bit field '" + bit.name() + "' must be at least 1 bit");
            total += bit.bits();
        }

        if (container != null && total > container.size() * 8) {
            problem(field, "the bit fields take " + total + " bits but " + container + " has only " + container.size() * 8);
        }
    }

    private void checkStruct(FieldDef field, Scope scope) {
        StructDef struct = field.struct();

        if (!visiting.add(struct)) {
            problem(field, "struct '" + struct.name() + "' contains itself");
            return;
        }

        try {
            if (struct.fields().isEmpty()) problem(field, "struct '" + struct.name() + "' has no fields");
            checkFields(struct.fields(), scope);
        } finally {
            visiting.remove(struct);
        }
    }

    private void checkArray(FieldDef field, List<FieldDef> earlierSiblings, Scope scope) {
        ArrayDef array = field.array();
        boolean hasLength = array.length() > 0;
        boolean hasField = array.lengthField() != null && !array.lengthField().isBlank();

        if (hasLength == hasField) {
            problem(field, "an array needs exactly one of length (> 0) or lengthField");
            return;
        }
        if (!hasField) return;

        String path = array.lengthField();
        FieldDef count;

        if (path.startsWith(HEADER_PREFIX)) {
            count = resolve(scope.headerFields(), path.substring(HEADER_PREFIX.length()));
        } else if (path.contains(".")) {
            count = resolve(scope.rootFields(), path);
        } else {
            count = byName(earlierSiblings, path);
        }

        if (count == null || count.kind() != EFieldKind.PRIMITIVE || !count.type().integer() || count.array() != null) {
            problem(field, "lengthField '" + path + "' must name an integer field before this one"
                    + " (a plain name in the same struct, 'header.x', or a dotted path from the message root)");
        }
    }

    /** Follows a dotted path through structs (not arrays). */
    private static FieldDef resolve(List<FieldDef> fields, String path) {
        String[] parts = path.split("\\.");
        List<FieldDef> current = fields;
        FieldDef field = null;

        for (String part : parts) {
            if (current == null) return null;
            field = byName(current, part);
            if (field == null || field.array() != null) return null;
            current = field.kind() == EFieldKind.STRUCT ? field.struct().fields() : null;
        }
        return field;
    }

    private static FieldDef byName(List<FieldDef> fields, String name) {
        for (FieldDef field : fields) {
            if (field.name() != null && field.name().equals(name)) return field;
        }
        return null;
    }

    /** True if {@code value} (a raw long; UINT64 values above Long.MAX_VALUE are negative) fits {@code type}. */
    private static boolean fits(long value, EFieldType type) {
        if (type == EFieldType.UINT64) return true;
        return value >= type.minValue() && value <= type.maxValue();
    }
}
