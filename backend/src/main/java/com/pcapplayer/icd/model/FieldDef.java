package com.pcapplayer.icd.model;

/**
 * One field of a message, header or struct. Only the attributes of its {@link EFieldKind} are set;
 * the others are null (or 0).
 *
 * @param order        position in the enclosing struct; fields are sorted by it
 * @param type         PRIMITIVE: the data type. Null for the other kinds (an enum or bitfield has its own type).
 * @param byteOrder    override for this field, null to inherit from the enclosing message or ICD
 * @param array        set when the field repeats; each element is described by the other attributes
 * @param paddingBytes PADDING: number of reserved bytes
 * @param rules        PRIMITIVE and ENUM: scaling and validity checks of the value; null for none
 */
public record FieldDef(String name, int order, EFieldKind kind, EFieldType type, EByteOrder byteOrder,
                       StructDef struct, EnumDef enumDef, StringDef string, BitfieldDef bitfield,
                       int paddingBytes, ArrayDef array, ValueRules rules) {

    public FieldDef(String name, int order, EFieldKind kind, EFieldType type, EByteOrder byteOrder,
                    StructDef struct, EnumDef enumDef, StringDef string, BitfieldDef bitfield,
                    int paddingBytes, ArrayDef array) {
        this(name, order, kind, type, byteOrder, struct, enumDef, string, bitfield, paddingBytes, array, null);
    }

    /** Value of {@link #fixedSize} when the size depends on another field's value. */
    public static final int VARIABLE = -1;

    public static FieldDef primitive(String name, int order, EFieldType type) {
        return new FieldDef(name, order, EFieldKind.PRIMITIVE, type, null, null, null, null, null, 0, null);
    }

    public static FieldDef ofEnum(String name, int order, EnumDef enumDef) {
        return new FieldDef(name, order, EFieldKind.ENUM, null, null, null, enumDef, null, null, 0, null);
    }

    public static FieldDef ofStruct(String name, int order, StructDef struct) {
        return new FieldDef(name, order, EFieldKind.STRUCT, null, null, struct, null, null, null, 0, null);
    }

    public static FieldDef ofString(String name, int order, StringDef string) {
        return new FieldDef(name, order, EFieldKind.STRING, null, null, null, null, string, null, 0, null);
    }

    public static FieldDef ofBitfield(String name, int order, BitfieldDef bitfield) {
        return new FieldDef(name, order, EFieldKind.BITFIELD, null, null, null, null, null, bitfield, 0, null);
    }

    public static FieldDef padding(String name, int order, int bytes) {
        return new FieldDef(name, order, EFieldKind.PADDING, null, null, null, null, null, null, bytes, null);
    }

    public FieldDef withByteOrder(EByteOrder newByteOrder) {
        return new FieldDef(name, order, kind, type, newByteOrder, struct, enumDef, string, bitfield, paddingBytes, array, rules);
    }

    public FieldDef withArray(ArrayDef newArray) {
        return new FieldDef(name, order, kind, type, byteOrder, struct, enumDef, string, bitfield, paddingBytes, newArray, rules);
    }

    public FieldDef withRules(ValueRules newRules) {
        return new FieldDef(name, order, kind, type, byteOrder, struct, enumDef, string, bitfield, paddingBytes, array, newRules);
    }

    /** The integer type the bytes are read as: the primitive type, the enum's type or the bitfield container. */
    public EFieldType storageType() {
        return switch (kind) {
            case PRIMITIVE -> type;
            case ENUM -> enumDef.type();
            case BITFIELD -> bitfield.type();
            default -> null;
        };
    }

    /** Size of one element in bytes, or {@link #VARIABLE}. */
    public int elementSize() {
        return switch (kind) {
            case PRIMITIVE, ENUM, BITFIELD -> storageType().size();
            case STRING -> string.length();
            case PADDING -> paddingBytes;
            case STRUCT -> fixedSize(struct.fields());
        };
    }

    /** Size of the whole field (all elements), or {@link #VARIABLE}. */
    public int fixedSize() {
        int element = elementSize();
        if (array == null || element == VARIABLE) return element;
        return array.variable() ? VARIABLE : element * array.length();
    }

    /** Total size of a list of fields, or {@link #VARIABLE} if any of them is variable. */
    public static int fixedSize(Iterable<FieldDef> fields) {
        int total = 0;

        for (FieldDef field : fields) {
            int size = field.fixedSize();
            if (size == VARIABLE) return VARIABLE;
            total += size;
        }
        return total;
    }
}
