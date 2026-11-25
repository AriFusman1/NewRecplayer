package com.pcapplayer.autotag.rules;

import com.pcapplayer.icd.tree.IcdTree;
import com.pcapplayer.icd.tree.IcdTreeMessage;
import com.pcapplayer.icd.tree.IcdTreeNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The rules of one run, compiled once and shared by every file (read-only, so thread-safe).
 * Conditions are grouped by opcode: a message is only tested against conditions on its own type.
 */
public final class CompiledRules {

    /** A rule and the indexes of its conditions' counters. */
    record CompiledRule(TagRule rule, int[] conditionIndexes) {
    }

    private final List<CompiledRule> rules;
    private final Map<Long, List<CompiledCondition>> byOpcode;
    private final int conditionCount;

    private CompiledRules(List<CompiledRule> rules, Map<Long, List<CompiledCondition>> byOpcode, int conditionCount) {
        this.rules = rules;
        this.byOpcode = byOpcode;
        this.conditionCount = conditionCount;
    }

    /**
     * @param rules     rules that passed {@link RuleValidator} against {@code tree}
     * @param tolerance absolute tolerance for float equality
     */
    public static CompiledRules compile(List<TagRule> rules, IcdTree tree, double tolerance) {
        List<CompiledRule> compiled = new ArrayList<>();
        Map<Long, List<CompiledCondition>> byOpcode = new HashMap<>();
        int index = 0;

        for (TagRule rule : rules) {
            int[] indexes = new int[rule.conditions().size()];

            for (int i = 0; i < indexes.length; i++) {
                RuleCondition condition = rule.conditions().get(i);
                IcdTreeMessage message = tree.message(condition.opcode());
                Map<String, IcdTreeNode> nodes = message.byPath();
                List<CompiledPredicate> predicates = new ArrayList<>();

                for (FieldPredicate predicate : condition.predicates()) {
                    EValueType type = EValueType.of(nodes.get(predicate.path()));
                    predicates.add(new CompiledPredicate(new PathPattern(predicate.path()),
                            PredicateEvaluator.compile(predicate, type, tolerance)));
                }
                byOpcode.computeIfAbsent(condition.opcode(), opcode -> new ArrayList<>())
                        .add(new CompiledCondition(index, condition, predicates));
                indexes[i] = index++;
            }
            compiled.add(new CompiledRule(rule, indexes));
        }
        return new CompiledRules(compiled, byOpcode, index);
    }

    List<CompiledRule> rules() {
        return rules;
    }

    List<CompiledCondition> conditionsFor(long opcode) {
        return byOpcode.getOrDefault(opcode, List.of());
    }

    int conditionCount() {
        return conditionCount;
    }
}
