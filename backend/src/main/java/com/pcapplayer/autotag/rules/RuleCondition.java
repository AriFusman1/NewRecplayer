package com.pcapplayer.autotag.rules;

import java.util.List;

/**
 * One condition of a rule, evaluated on the counts after the whole file is decoded.
 *
 * @param opcode     the message type
 * @param minCount   how many messages it takes (at least 1)
 * @param predicates for {@link EConditionKind#FIELDS}: all must hold for a message to count; empty for COUNT
 */
public record RuleCondition(EConditionKind kind, long opcode, long minCount, List<FieldPredicate> predicates) {

    public static RuleCondition count(long opcode, long minCount) {
        return new RuleCondition(EConditionKind.COUNT, opcode, minCount, List.of());
    }

    public static RuleCondition fields(long opcode, long minCount, FieldPredicate... predicates) {
        return new RuleCondition(EConditionKind.FIELDS, opcode, minCount, List.of(predicates));
    }
}
