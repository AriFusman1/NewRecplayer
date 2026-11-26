package com.pcapplayer.icdConverter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The parameters of one annotation as JSON values, keyed by lower-case name so that the spellings
 * found in real code ({@code MaxValue} / {@code maxValue}) are the same. A single unnamed parameter
 * ({@code @Ordinal(3)}) is under {@code "value"}.
 */
final class AnnotationValues {

    private final Map<String, JsonNode> values = new LinkedHashMap<>();

    private AnnotationValues() {
    }

    static AnnotationValues of(AnnotationExpr annotation) {
        AnnotationValues result = new AnnotationValues();

        if (annotation instanceof SingleMemberAnnotationExpr single) {
            result.values.put("value", toJson(single.getMemberValue()));
        } else if (annotation instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                result.values.put(pair.getNameAsString().toLowerCase(Locale.ROOT), toJson(pair.getValue()));
            }
        }
        return result;
    }

    /** The annotation's simple name in lower case: {@code @io.Ordinal} and {@code @ordinal} are both "ordinal". */
    static String nameOf(AnnotationExpr annotation) {
        return annotation.getName().getIdentifier().toLowerCase(Locale.ROOT);
    }

    /** @param names the parameter's possible names, any case */
    JsonNode get(String... names) {
        for (String name : names) {
            JsonNode value = values.get(name.toLowerCase(Locale.ROOT));
            if (value != null) return value;
        }
        return null;
    }

    boolean isEmpty() {
        return values.isEmpty();
    }

    /**
     * A literal as JSON: numbers, strings (trimmed: {@code "numOfReports "} is a typo for the name),
     * booleans, {@code int.class} as "int", and an enum constant ({@code BitfieldLength.BITS8}) as its name.
     */
    private static JsonNode toJson(Expression expression) {
        if (expression instanceof IntegerLiteralExpr integer) return IntNode.valueOf(integer.asNumber().intValue());
        if (expression instanceof LongLiteralExpr longValue) return LongNode.valueOf(longValue.asNumber().longValue());
        if (expression instanceof DoubleLiteralExpr decimal) return DoubleNode.valueOf(decimal.asDouble());
        if (expression instanceof BooleanLiteralExpr bool) return BooleanNode.valueOf(bool.getValue());
        if (expression instanceof StringLiteralExpr text) return TextNode.valueOf(text.asString().trim());
        if (expression instanceof ClassExpr type) return TextNode.valueOf(type.getType().asString());
        if (expression instanceof FieldAccessExpr constant) return TextNode.valueOf(constant.getNameAsString());

        if (expression instanceof UnaryExpr unary && unary.getOperator() == UnaryExpr.Operator.MINUS) {
            JsonNode inner = toJson(unary.getExpression());
            if (inner.isInt()) return IntNode.valueOf(-inner.intValue());
            if (inner.isLong()) return LongNode.valueOf(-inner.longValue());
            if (inner.isDouble()) return DoubleNode.valueOf(-inner.doubleValue());
        }
        return TextNode.valueOf(expression.toString()); // a constant's name: kept so the problem is visible
    }
}
