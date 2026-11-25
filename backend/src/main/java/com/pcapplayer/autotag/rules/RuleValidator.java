package com.pcapplayer.autotag.rules;

import com.pcapplayer.common.Strings;
import com.pcapplayer.icd.model.EFieldType;
import com.pcapplayer.icd.tree.ETreeNodeKind;
import com.pcapplayer.icd.tree.IcdTree;
import com.pcapplayer.icd.tree.IcdTreeEnumValue;
import com.pcapplayer.icd.tree.IcdTreeMessage;
import com.pcapplayer.icd.tree.IcdTreeNode;
import com.pcapplayer.pcapProcessing.diagnostics.Warning;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Checks a rule against the ICD tree of its category. Every error names where it is: the rule, the
 * condition number (from 1) and the path. Also fills in the number of every enum value chosen by name.
 */
public final class RuleValidator {

    private static final BigInteger UINT64_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

    private RuleValidator() {
    }

    public static RuleCheck check(TagRule rule, IcdTree tree) {
        List<Warning> errors = new ArrayList<>();
        String name = rule == null || rule.name() == null ? "" : rule.name().trim();
        if (name.isEmpty()) errors.add(Warning.of("TAGRULE_NAME_REQUIRED"));
        List<RuleCondition> conditions = rule == null || rule.conditions() == null ? List.of() : rule.conditions();
        if (conditions.isEmpty()) errors.add(Warning.of("TAGRULE_NO_CONDITIONS", "rule", name));
        List<RuleCondition> normalized = new ArrayList<>();

        for (int i = 0; i < conditions.size(); i++) {
            normalized.add(checkCondition(conditions.get(i), name, i + 1, tree, errors));
        }
        return new RuleCheck(new TagRule(rule == null ? null : rule.id(), name, normalized), errors);
    }

    private static RuleCondition checkCondition(RuleCondition condition, String rule, int number, IcdTree tree, List<Warning> errors) {
        if (condition == null || condition.kind() == null) {
            errors.add(Warning.of("TAGRULE_BAD_CONDITION", "rule", rule, "condition", number));
            return condition;
        }
        if (condition.minCount() < 1) errors.add(Warning.of("TAGRULE_MIN_COUNT", "rule", rule, "condition", number));
        IcdTreeMessage message = tree.message(condition.opcode());

        if (message == null) {
            errors.add(Warning.of("TAGRULE_UNKNOWN_OPCODE", "rule", rule, "condition", number, "opcode", condition.opcode()));
            return condition;
        }
        List<FieldPredicate> predicates = condition.predicates() == null ? List.of() : condition.predicates();

        if (condition.kind() == EConditionKind.COUNT) {
            if (!predicates.isEmpty()) errors.add(Warning.of("TAGRULE_COUNT_WITH_PREDICATES", "rule", rule, "condition", number));
            return new RuleCondition(condition.kind(), condition.opcode(), condition.minCount(), List.of());
        }
        if (predicates.isEmpty()) errors.add(Warning.of("TAGRULE_NO_PREDICATES", "rule", rule, "condition", number));
        Map<String, IcdTreeNode> nodes = message.byPath();
        List<FieldPredicate> normalized = new ArrayList<>();
        for (FieldPredicate predicate : predicates) normalized.add(checkPredicate(predicate, nodes, rule, number, errors));
        return new RuleCondition(condition.kind(), condition.opcode(), condition.minCount(), normalized);
    }

    private static FieldPredicate checkPredicate(FieldPredicate predicate, Map<String, IcdTreeNode> nodes, String rule, int number,
                                                 List<Warning> errors) {
        String path = predicate == null ? null : predicate.path();
        IcdTreeNode node = path == null ? null : nodes.get(path);

        if (node == null) {
            errors.add(Warning.of("TAGRULE_UNKNOWN_PATH", "rule", rule, "condition", number, "path", String.valueOf(path)));
            return predicate;
        }
        EValueType type = EValueType.of(node);

        if (type == null) {
            errors.add(Warning.of("TAGRULE_NOT_A_VALUE", "rule", rule, "condition", number, "path", path));
            return predicate;
        }

        if (predicate.op() == null || !type.allows(predicate.op())) {
            errors.add(Warning.of("TAGRULE_BAD_OPERATOR", "rule", rule, "condition", number, "path", path,
                    "op", String.valueOf(predicate.op()), "type", type.name()));
            return predicate;
        }
        List<RuleValue> values = predicate.values() == null ? List.of() : predicate.values();
        int expected = predicate.op().valueCount();

        if (expected > 0 ? values.size() != expected : values.isEmpty()) {
            errors.add(Warning.of("TAGRULE_VALUE_COUNT", "rule", rule, "condition", number, "path", path,
                    "op", predicate.op().name(), "count", values.size()));
            return predicate;
        }
        List<RuleValue> normalized = new ArrayList<>();

        for (RuleValue value : values) {
            RuleValue checked = checkValue(value, node, type, rule, number, path, errors);
            normalized.add(checked);
        }

        if (predicate.op() == EOperator.BETWEEN && normalized.stream().allMatch(value -> value != null && value.number() != null)
                && normalized.get(0).number().compareTo(normalized.get(1).number()) > 0) {
            errors.add(Warning.of("TAGRULE_BAD_RANGE", "rule", rule, "condition", number, "path", path));
        }
        return new FieldPredicate(path, predicate.op(), normalized);
    }

    /** Returns the value as stored (enum: with its number), or the input when it is invalid (an error is added). */
    private static RuleValue checkValue(RuleValue value, IcdTreeNode node, EValueType type, String rule, int number, String path,
                                        List<Warning> errors) {
        if (value == null) {
            errors.add(badValue(rule, number, path, "null"));
            return null;
        }

        switch (type) {
            case INTEGER -> {
                if (value.number() == null || !PredicateEvaluator.isWhole(value.number())) {
                    errors.add(badValue(rule, number, path, String.valueOf(value.number())));
                    return value;
                }
                BigInteger integer = value.number().toBigIntegerExact();
                BigInteger[] range = integerRange(node);

                if (integer.compareTo(range[0]) < 0 || integer.compareTo(range[1]) > 0) {
                    errors.add(Warning.of("TAGRULE_OUT_OF_RANGE", "rule", rule, "condition", number, "path", path,
                            "value", integer.toString(), "min", range[0].toString(), "max", range[1].toString()));
                }
                return value.withNumber(new BigDecimal(integer));
            }
            case FLOAT -> {
                if (value.number() == null) errors.add(badValue(rule, number, path, "null"));
                return value;
            }
            case ENUM -> {
                IcdTreeEnumValue match = node.enumValues().stream()
                        .filter(entry -> entry.name().equals(value.name())).findFirst().orElse(null);

                if (match == null) {
                    errors.add(Warning.of("TAGRULE_UNKNOWN_ENUM", "rule", rule, "condition", number, "path", path,
                            "value", String.valueOf(value.name())));
                    return value;
                }
                return new RuleValue(BigDecimal.valueOf(match.value()), match.name(), null, null);
            }
            case BOOL -> {
                if (value.bool() == null) errors.add(badValue(rule, number, path, "null"));
                return value;
            }
            case STRING -> {
                if (value.text() == null) errors.add(badValue(rule, number, path, "null"));
                return value;
            }
            default -> throw new IllegalStateException(type.name());
        }
    }

    /** The smallest and largest value the field can hold: its storage type, or the bit width for a bit. */
    static BigInteger[] integerRange(IcdTreeNode node) {
        if (node.kind() == ETreeNodeKind.BIT) {
            return new BigInteger[]{BigInteger.ZERO, BigInteger.ONE.shiftLeft(node.bitWidth()).subtract(BigInteger.ONE)};
        }
        EFieldType type = node.storageType();
        if (type == EFieldType.UINT64) return new BigInteger[]{BigInteger.ZERO, UINT64_MAX};
        return new BigInteger[]{BigInteger.valueOf(type.minValue()), BigInteger.valueOf(type.maxValue())};
    }

    private static Warning badValue(String rule, int number, String path, String value) {
        return Warning.of("TAGRULE_BAD_VALUE", "rule", rule, "condition", number, "path", path, "value", Strings.orDefault(value, ""));
    }
}
