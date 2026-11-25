package com.pcapplayer.icdConverter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * One annotated Java field as a field of the annotation-style ICD JSON: one JSON property per
 * annotation, its parameters as the property's value (docs/icd-annotations-json.md).
 */
final class FieldConverter {

    /** Annotations that say nothing about the wire format. */
    private static final Set<String> IGNORED = Set.of("override", "deprecated", "suppresswarnings", "jsonignore");

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final List<String> warnings;

    FieldConverter(List<String> warnings) {
        this.warnings = warnings;
    }

    /**
     * @param where "Class.field", for warnings
     * @return the JSON field, or empty when the field is not on the wire (static, transient, or no {@code @Ordinal})
     */
    Optional<ObjectNode> convert(FieldDeclaration declaration, VariableDeclarator variable, String where) {
        if (declaration.isStatic() || declaration.isTransient() || has(declaration, "transient")) return Optional.empty();

        if (!has(declaration, "ordinal")) {
            warnings.add(where + ": no @Ordinal, so it is not on the wire; skipped");
            return Optional.empty();
        }
        ObjectNode field = JSON.objectNode();
        field.put("name", variable.getNameAsString());
        field.put("type", variable.getType().asString());

        for (AnnotationExpr annotation : declaration.getAnnotations()) {
            addAnnotation(field, annotation, where);
        }
        Javadocs.description(declaration).ifPresent(text -> field.put("description", text));
        return Optional.of(field);
    }

    private void addAnnotation(ObjectNode field, AnnotationExpr annotation, String where) {
        AnnotationValues values = AnnotationValues.of(annotation);

        switch (AnnotationValues.nameOf(annotation)) {
            case "ordinal" -> field.set("ordinal", values.get("value"));
            case "unsigned" -> field.put("unsigned", true);
            case "bits" -> field.set("bits", bits(values));
            case "bit" -> field.put("bit", true);
            case "size" -> field.set("size", values.get("value"));
            case "enumerable" -> enumerable(field, values);
            case "fixedpoint" -> field.set("fixedPoint", object(values, "lsb", "lsb", "type", "type"));
            case "validrange" -> field.set("validRange", object(values, "minValue", "minValue", "maxValue", "maxValue"));
            case "invalidityvalue" -> field.set("invalidityValue", values.get("value"));
            case "lengthencoded" -> field.set("lengthEncoded",
                    object(values, "encodingField", "encodingField", "value", "value", "unsigned", "unsigned"));
            case "lengthencoder", "lenghtencoder" -> field.set("lengthEncoder", object(values, "isLengthInBytes", "isLengthInBytes"));
            case "binaryinternal" -> field.put("binaryInternal", true);

            case "existenceencoded" -> {
                field.set("existenceEncoded", object(values, "method", "method", "params", "params", "isAbsolutePath", "isAbsolutePath"));
                warnings.add(where + ": @ExistenceEncoded is decided by code; the message is decoded only up to this field");
            }
            case "custom" -> {
                field.set("custom", values.get("value"));
                warnings.add(where + ": @Custom is read by code; the message is decoded only up to this field");
            }
            case "encoded" -> {
                field.set("encoded", object(values, "codec", "codec", "underlyingType", "underlyingType"));
                warnings.add(where + ": @Encoded is read by a codec; the message is decoded only up to this field");
            }
            default -> {
                if (!IGNORED.contains(AnnotationValues.nameOf(annotation))) {
                    warnings.add(where + ": unknown annotation @" + annotation.getNameAsString() + " ignored");
                }
            }
        }
    }

    /** {@code @Bits(value = 5, bitfield = BitfieldLength.BITS8)}; the chunk is written BITS8/16/24/32 even if the code says BIT8. */
    private static ObjectNode bits(AnnotationValues values) {
        ObjectNode bits = JSON.objectNode();
        bits.set("value", values.get("value"));
        JsonNode length = values.get("bitfield", "bitfieldLength");
        if (length != null) bits.put("bitfield", length.asText().replaceFirst("^BITS?", "BITS"));
        return bits;
    }

    /** {@code @Enumerable(byte.class)} gives the size; a bare {@code @Enumerable} (sized by {@code @Bits}) adds nothing. */
    private static void enumerable(ObjectNode field, AnnotationValues values) {
        JsonNode size = values.get("value");
        if (size != null) field.put("enumerable", size.asText().replace(".class", ""));
    }

    /**
     * The given parameters as an object.
     *
     * @param names pairs of (JSON name, annotation parameter name); parameters that are not set are left out
     */
    private static ObjectNode object(AnnotationValues values, String... names) {
        ObjectNode object = JSON.objectNode();

        for (int i = 0; i < names.length; i += 2) {
            JsonNode value = values.get(names[i + 1]);
            if (value != null) object.set(names[i], value);
        }
        return object;
    }

    private static boolean has(FieldDeclaration declaration, String annotationName) {
        return declaration.getAnnotations().stream().anyMatch(annotation -> AnnotationValues.nameOf(annotation).equals(annotationName));
    }
}
