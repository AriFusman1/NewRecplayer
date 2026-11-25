package com.pcapplayer.icd.loading;

import com.pcapplayer.icd.annotations.Icd;
import com.pcapplayer.icd.annotations.IcdArray;
import com.pcapplayer.icd.annotations.IcdBitfield;
import com.pcapplayer.icd.annotations.IcdBits;
import com.pcapplayer.icd.annotations.IcdEnum;
import com.pcapplayer.icd.annotations.IcdEnumValue;
import com.pcapplayer.icd.annotations.IcdField;
import com.pcapplayer.icd.annotations.IcdHeader;
import com.pcapplayer.icd.annotations.IcdMessage;
import com.pcapplayer.icd.annotations.IcdPadding;
import com.pcapplayer.icd.annotations.IcdString;
import com.pcapplayer.icd.annotations.Length;
import com.pcapplayer.icd.annotations.Opcode;
import com.pcapplayer.icd.model.ArrayDef;
import com.pcapplayer.icd.model.BitDef;
import com.pcapplayer.icd.model.BitfieldDef;
import com.pcapplayer.icd.model.EFieldType;
import com.pcapplayer.icd.model.EnumDef;
import com.pcapplayer.icd.model.FieldDef;
import com.pcapplayer.icd.model.HeaderDef;
import com.pcapplayer.icd.model.IcdDefinition;
import com.pcapplayer.icd.model.MessageDef;
import com.pcapplayer.icd.model.StringDef;
import com.pcapplayer.icd.model.StructDef;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds an {@link IcdDefinition} from annotated Java classes by reflection. The classes are only read,
 * never instantiated. Fields are ordered by {@link IcdField#order()} alone.
 * <p>
 * How a field's kind is chosen, in this order: {@link IcdPadding} → padding; {@link IcdString} → string;
 * an explicit {@code type} → primitive; a Java enum with {@link IcdEnum} → enum; a class with
 * {@link IcdBitfield} → bitfield; a Java primitive or boxed number → primitive of the matching signed
 * type; any other class with {@link IcdField} fields → nested struct. For arrays the component type is used.
 */
public final class AnnotationIcdLoader {

    private static final Map<Class<?>, EFieldType> JAVA_TYPES = Map.ofEntries(
            Map.entry(byte.class, EFieldType.INT8), Map.entry(Byte.class, EFieldType.INT8),
            Map.entry(short.class, EFieldType.INT16), Map.entry(Short.class, EFieldType.INT16),
            Map.entry(int.class, EFieldType.INT32), Map.entry(Integer.class, EFieldType.INT32),
            Map.entry(long.class, EFieldType.INT64), Map.entry(Long.class, EFieldType.INT64),
            Map.entry(float.class, EFieldType.FLOAT32), Map.entry(Float.class, EFieldType.FLOAT32),
            Map.entry(double.class, EFieldType.FLOAT64), Map.entry(Double.class, EFieldType.FLOAT64),
            Map.entry(boolean.class, EFieldType.BOOL), Map.entry(Boolean.class, EFieldType.BOOL));

    private final List<IcdProblem> problems = new ArrayList<>();
    private final Map<Object, String> locations = new IdentityHashMap<>();
    private final Map<Class<?>, StructDef> structs = new HashMap<>();
    private final Map<Class<?>, EnumDef> enums = new HashMap<>();
    private final Set<Class<?>> building = new HashSet<>();

    private AnnotationIcdLoader() {
    }

    /** Loads the ICD whose holder class is annotated with {@link Icd}. */
    public static IcdDefinition load(Class<?> holder) throws IcdLoadException {
        return new AnnotationIcdLoader().loadIcd(holder);
    }

    private IcdDefinition loadIcd(Class<?> holder) throws IcdLoadException {
        Icd icd = holder.getAnnotation(Icd.class);
        if (icd == null) throw new IcdLoadException(List.of(new IcdProblem(holder.getName(), "missing @Icd")));

        HeaderDef header = loadHeader(icd.header());
        List<MessageDef> messages = new ArrayList<>();
        for (Class<?> messageClass : icd.messages()) messages.add(loadMessage(messageClass));
        messages.sort(Comparator.comparingLong(MessageDef::opcode));
        IcdDefinition definition = new IcdDefinition(icd.id(), icd.name(), icd.byteOrder(), header, messages);
        locations.put(definition, holder.getSimpleName());
        if (!problems.isEmpty()) throw new IcdLoadException(problems);

        List<IcdProblem> invalid = IcdModelValidator.validate(definition,
                IcdModelValidator.locator(locations, holder.getSimpleName()));
        if (!invalid.isEmpty()) throw new IcdLoadException(invalid);
        return definition;
    }

    private void problem(String where, String message) {
        problems.add(new IcdProblem(where, message));
    }

    private HeaderDef loadHeader(Class<?> headerClass) {
        IcdHeader annotation = headerClass.getAnnotation(IcdHeader.class);
        if (annotation == null) problem(headerClass.getSimpleName(), "missing @IcdHeader");
        List<FieldDef> fields = loadFields(headerClass);
        String opcode = markedField(headerClass, Opcode.class);
        String length = markedField(headerClass, Length.class);

        HeaderDef header = annotation == null
                ? new HeaderDef(fields, opcode, length, true, null, HeaderDef.DEFAULT_MAX_MESSAGE_BYTES)
                : new HeaderDef(fields, opcode, length, annotation.lengthIncludesHeader(), annotation.lengthUnit(),
                annotation.maxMessageBytes());
        locations.put(header, headerClass.getSimpleName());
        return header;
    }

    /** Name of the single field carrying {@code marker}, or null (the validator reports it). */
    private String markedField(Class<?> headerClass, Class<? extends java.lang.annotation.Annotation> marker) {
        List<String> names = annotatedFields(headerClass).stream()
                .filter(field -> field.isAnnotationPresent(marker))
                .map(Field::getName)
                .toList();
        if (names.size() > 1) problem(headerClass.getSimpleName(), "more than one field has @" + marker.getSimpleName());
        return names.isEmpty() ? null : names.get(0);
    }

    private MessageDef loadMessage(Class<?> messageClass) {
        IcdMessage annotation = messageClass.getAnnotation(IcdMessage.class);

        if (annotation == null) {
            problem(messageClass.getSimpleName(), "missing @IcdMessage");
            return new MessageDef(-1, messageClass.getSimpleName(), null, List.of());
        }
        MessageDef message = new MessageDef(annotation.opcode(), annotation.name(),
                single(annotation.byteOrder(), messageClass.getSimpleName(), "byteOrder"), loadFields(messageClass));
        locations.put(message, messageClass.getSimpleName());
        return message;
    }

    /** The class's {@link IcdField} fields, sorted by order. */
    private List<FieldDef> loadFields(Class<?> owner) {
        List<FieldDef> fields = new ArrayList<>();
        for (Field javaField : annotatedFields(owner)) fields.add(loadField(owner, javaField));
        fields.sort(Comparator.comparingInt(FieldDef::order));
        return fields;
    }

    private List<Field> annotatedFields(Class<?> owner) {
        List<Field> result = new ArrayList<>();

        for (Field javaField : owner.getDeclaredFields()) {
            if (Modifier.isStatic(javaField.getModifiers()) || javaField.isSynthetic()) continue;

            if (javaField.isAnnotationPresent(IcdField.class)) {
                result.add(javaField);
            } else if (hasIcdAnnotation(javaField)) {
                problem(where(owner, javaField), "missing @IcdField(order = ...)");
            }
        }
        return result;
    }

    private static boolean hasIcdAnnotation(Field javaField) {
        return Arrays.stream(javaField.getAnnotations())
                .anyMatch(annotation -> annotation.annotationType().getPackage().equals(Icd.class.getPackage()));
    }

    private FieldDef loadField(Class<?> owner, Field javaField) {
        String where = where(owner, javaField);
        IcdField annotation = javaField.getAnnotation(IcdField.class);
        String name = javaField.getName();
        int order = annotation.order();
        IcdArray arrayAnnotation = javaField.getAnnotation(IcdArray.class);
        Class<?> elementType = javaField.getType();
        IcdPadding padding = javaField.getAnnotation(IcdPadding.class);

        if (arrayAnnotation != null) {
            if (elementType.isArray()) elementType = elementType.getComponentType();
            else problem(where, "@IcdArray needs a Java array type");
        } else if (elementType.isArray() && padding == null) {
            problem(where, "a Java array needs @IcdArray(length = ...) or @IcdArray(lengthField = ...)");
            elementType = elementType.getComponentType();
        }

        FieldDef field = elementField(where, name, order, javaField, elementType, single(annotation.type(), where, "type"));
        if (field == null) field = FieldDef.primitive(name, order, null); // reported already
        field = field.withByteOrder(single(annotation.byteOrder(), where, "byteOrder"));
        if (arrayAnnotation != null) field = field.withArray(arrayOf(arrayAnnotation));
        locations.put(field, where);
        return field;
    }

    /** One element of the field, without byte order or array. Null after reporting a problem. */
    private FieldDef elementField(String where, String name, int order, Field javaField, Class<?> elementType,
                                  EFieldType explicitType) {
        IcdPadding padding = javaField.getAnnotation(IcdPadding.class);
        if (padding != null) return FieldDef.padding(name, order, padding.bytes());

        IcdString string = javaField.getAnnotation(IcdString.class);
        if (string != null) return FieldDef.ofString(name, order, new StringDef(string.length(), string.encoding()));
        if (explicitType != null) return FieldDef.primitive(name, order, explicitType);

        if (elementType.isEnum() && elementType.isAnnotationPresent(IcdEnum.class)) {
            return FieldDef.ofEnum(name, order, enumOf(elementType));
        }
        if (elementType.isAnnotationPresent(IcdBitfield.class)) return FieldDef.ofBitfield(name, order, bitfieldOf(elementType));
        if (JAVA_TYPES.containsKey(elementType)) return FieldDef.primitive(name, order, JAVA_TYPES.get(elementType));

        if (!elementType.isPrimitive() && !elementType.isEnum() && elementType != String.class) {
            return FieldDef.ofStruct(name, order, structOf(where, elementType));
        }
        problem(where, "cannot tell the wire type of Java type " + elementType.getSimpleName()
                + "; add type = ... or the matching annotation");
        return null;
    }

    private StructDef structOf(String where, Class<?> structClass) {
        StructDef known = structs.get(structClass);
        if (known != null) return known;

        if (!building.add(structClass)) {
            problem(where, "struct " + structClass.getSimpleName() + " contains itself");
            return new StructDef(structClass.getSimpleName(), List.of());
        }

        try {
            List<FieldDef> fields = loadFields(structClass);
            if (fields.isEmpty()) problem(where, structClass.getSimpleName() + " has no @IcdField fields");
            StructDef struct = new StructDef(structClass.getSimpleName(), fields);
            structs.put(structClass, struct);
            return struct;
        } finally {
            building.remove(structClass);
        }
    }

    private EnumDef enumOf(Class<?> enumClass) {
        EnumDef known = enums.get(enumClass);
        if (known != null) return known;

        TreeMap<Long, String> values = new TreeMap<>();

        for (Object constant : enumClass.getEnumConstants()) {
            String constantName = ((Enum<?>) constant).name();
            IcdEnumValue value = enumValueOf(enumClass, constantName);

            if (value == null) {
                problem(enumClass.getSimpleName() + "." + constantName, "missing @IcdEnumValue");
            } else if (values.putIfAbsent(value.value(), constantName) != null) {
                problem(enumClass.getSimpleName() + "." + constantName, "duplicate enum value " + value.value());
            }
        }
        IcdEnum annotation = enumClass.getAnnotation(IcdEnum.class);
        EnumDef enumDef = new EnumDef(enumName(enumClass, annotation), annotation.type(), values);
        enums.put(enumClass, enumDef);
        return enumDef;
    }

    /** {@code EStatus} → {@code Status} unless the annotation names it. */
    private static String enumName(Class<?> enumClass, IcdEnum annotation) {
        if (!annotation.name().isEmpty()) return annotation.name();
        String javaName = enumClass.getSimpleName();
        boolean prefixed = javaName.length() > 1 && javaName.charAt(0) == 'E' && Character.isUpperCase(javaName.charAt(1));
        return prefixed ? javaName.substring(1) : javaName;
    }

    private static IcdEnumValue enumValueOf(Class<?> enumClass, String constantName) {
        try {
            return enumClass.getField(constantName).getAnnotation(IcdEnumValue.class);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    private BitfieldDef bitfieldOf(Class<?> bitfieldClass) {
        IcdBitfield annotation = bitfieldClass.getAnnotation(IcdBitfield.class);
        List<Field> javaFields = new ArrayList<>(annotatedFields(bitfieldClass));
        javaFields.sort(Comparator.comparingInt(javaField -> javaField.getAnnotation(IcdField.class).order()));
        List<BitDef> bits = new ArrayList<>();

        for (Field javaField : javaFields) {
            IcdBits width = javaField.getAnnotation(IcdBits.class);
            if (width == null) problem(where(bitfieldClass, javaField), "a bitfield member needs @IcdBits");
            else bits.add(new BitDef(javaField.getName(), width.value()));
        }
        return new BitfieldDef(annotation.type(), annotation.bitOrder(), bits);
    }

    private static ArrayDef arrayOf(IcdArray annotation) {
        return annotation.lengthField().isEmpty() ? ArrayDef.fixed(annotation.length())
                : new ArrayDef(annotation.length(), annotation.lengthField());
    }

    /** The single value of an optional one-element annotation attribute, or null. */
    private <T> T single(T[] values, String where, String attribute) {
        if (values.length > 1) problem(where, attribute + " takes one value");
        return values.length == 0 ? null : values[0];
    }

    private static String where(Class<?> owner, Field javaField) {
        return owner.getSimpleName() + "." + javaField.getName();
    }
}
