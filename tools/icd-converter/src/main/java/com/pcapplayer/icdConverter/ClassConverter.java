package com.pcapplayer.icdConverter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;

import java.util.List;
import java.util.Optional;

/** One annotated class, or one enum, as an entry of {@code classes} / {@code enums} in the ICD JSON. */
final class ClassConverter {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final FieldConverter fields;
    private final List<String> warnings;

    ClassConverter(List<String> warnings) {
        this.fields = new FieldConverter(warnings);
        this.warnings = warnings;
    }

    /** The class's own annotations ({@code @BinaryData}, {@code @Validated}, {@code @Charset}) and its fields, in source order. */
    ObjectNode convertClass(TypeDeclaration<?> type) {
        ObjectNode result = JSON.objectNode();

        for (AnnotationExpr annotation : type.getAnnotations()) {
            AnnotationValues values = AnnotationValues.of(annotation);

            switch (AnnotationValues.nameOf(annotation)) {
                case "binarydata" -> result.put("binaryData", true);
                case "validated" -> result.put("validated", true);
                case "charset" -> result.set("charset", charset(values));
                default -> warnings.add(type.getNameAsString() + ": class annotation @" + annotation.getNameAsString() + " ignored");
            }
        }
        Javadocs.description(type).ifPresent(text -> result.put("description", text));
        ArrayNode fieldNodes = result.putArray("fields");

        for (FieldDeclaration declaration : type.getFields()) {
            for (VariableDeclarator variable : declaration.getVariables()) {
                String where = type.getNameAsString() + "." + variable.getNameAsString();
                fields.convert(declaration, variable, where).ifPresent(fieldNodes::add);
            }
        }
        return result;
    }

    private static ObjectNode charset(AnnotationValues values) {
        ObjectNode charset = JSON.objectNode();
        charset.set("value", values.get("value"));
        JsonNode reversed = values.get("isReversed");
        if (reversed != null) charset.set("isReversed", reversed);
        return charset;
    }

    /**
     * An enum's values: its constants in order (numbered 0, 1, 2 ... like Java ordinals), or for an enum
     * that implements {@code IntValued}, each constant's own number (its first constructor argument).
     */
    ObjectNode convertEnum(EnumDeclaration declaration) {
        ObjectNode result = JSON.objectNode();
        boolean intValued = declaration.getImplementedTypes().stream().anyMatch(type -> type.getNameAsString().equals("IntValued"));

        if (intValued) {
            ObjectNode values = result.putObject("intValued");

            for (EnumConstantDeclaration constant : declaration.getEntries()) {
                Optional<Long> value = firstIntArgument(constant);
                if (value.isPresent()) values.put(constant.getNameAsString(), value.get());
                else warnings.add(declaration.getNameAsString() + "." + constant.getNameAsString() + ": IntValued but no int argument; skipped");
            }
        } else {
            ArrayNode constants = result.putArray("constants");
            declaration.getEntries().forEach(constant -> constants.add(constant.getNameAsString()));
        }
        Javadocs.description(declaration).ifPresent(text -> result.put("description", text));
        return result;
    }

    private static Optional<Long> firstIntArgument(EnumConstantDeclaration constant) {
        if (constant.getArguments().isEmpty()) return Optional.empty();

        Expression argument = constant.getArgument(0);
        if (argument instanceof IntegerLiteralExpr integer) return Optional.of(integer.asNumber().longValue());

        if (argument instanceof UnaryExpr unary && unary.getOperator() == UnaryExpr.Operator.MINUS
                && unary.getExpression() instanceof IntegerLiteralExpr integer) {
            return Optional.of(-integer.asNumber().longValue());
        }
        return Optional.empty();
    }
}
