package com.pcapplayer.autotag.rules;

import com.pcapplayer.icd.tree.IcdTree;
import com.pcapplayer.icd.tree.IcdTreeBuilder;
import com.pcapplayer.pcapProcessing.diagnostics.Warning;
import com.pcapplayer.testSupport.SampleIcdMessages;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleValidatorTest {

    private static final IcdTree TREE = IcdTreeBuilder.of(SampleIcdMessages.icd());

    private static List<String> errors(RuleCondition... conditions) {
        return RuleValidator.check(new TagRule(null, "r", List.of(conditions)), TREE).errors().stream().map(Warning::code).toList();
    }

    private static RuleCondition track(FieldPredicate... predicates) {
        return RuleCondition.fields(2, 1, predicates);
    }

    private static FieldPredicate predicate(String path, EOperator op, RuleValue... values) {
        return new FieldPredicate(path, op, List.of(values));
    }

    @Test
    void validRuleFillsInEnumNumbers() {
        RuleCheck check = RuleValidator.check(new TagRule(null, "  air  ", List.of(track(
                predicate("targets[*].kind", EOperator.IN, RuleValue.enumName("AIR"), RuleValue.enumName("UNKNOWN"))))), TREE);

        assertTrue(check.valid(), check.errors().toString());
        assertEquals("air", check.rule().name());
        List<RuleValue> values = check.rule().conditions().get(0).predicates().get(0).values();
        assertEquals(0, BigDecimal.ONE.compareTo(values.get(0).number()));
        assertEquals(0, BigDecimal.valueOf(-1).compareTo(values.get(1).number()));
    }

    @Test
    void ruleLevelErrors() {
        assertEquals(List.of("TAGRULE_NAME_REQUIRED", "TAGRULE_NO_CONDITIONS"),
                RuleValidator.check(new TagRule(null, " ", List.of()), TREE).errors().stream().map(Warning::code).toList());
        assertEquals(List.of("TAGRULE_UNKNOWN_OPCODE"), errors(RuleCondition.count(99, 1)));
        assertEquals(List.of("TAGRULE_MIN_COUNT"), errors(RuleCondition.count(1, 0)));
        assertEquals(List.of("TAGRULE_NO_PREDICATES"), errors(track()));
    }

    @Test
    void pathAndOperatorErrors() {
        assertEquals(List.of("TAGRULE_UNKNOWN_PATH"), errors(track(predicate("nope", EOperator.EQ, RuleValue.number(1)))));
        assertEquals(List.of("TAGRULE_UNKNOWN_PATH"), errors(track(predicate("targets[0].kind", EOperator.EQ, RuleValue.enumName("AIR")))),
                "array paths are written [*]");
        assertEquals(List.of("TAGRULE_NOT_A_VALUE"), errors(track(predicate("targets[*].pos", EOperator.EQ, RuleValue.number(1)))));
        assertEquals(List.of("TAGRULE_BAD_OPERATOR"), errors(track(predicate("targets[*].kind", EOperator.GT, RuleValue.enumName("AIR")))));
        assertEquals(List.of("TAGRULE_BAD_OPERATOR"), errors(RuleCondition.fields(1, 1, predicate("healthy", EOperator.NE, RuleValue.bool(true)))));
        assertEquals(List.of("TAGRULE_BAD_OPERATOR"), errors(track(predicate("targetCount", EOperator.CONTAINS, RuleValue.number(1)))));
    }

    @Test
    void valueErrors() {
        assertEquals(List.of("TAGRULE_VALUE_COUNT"), errors(track(predicate("targetCount", EOperator.BETWEEN, RuleValue.number(1)))));
        assertEquals(List.of("TAGRULE_VALUE_COUNT"), errors(track(predicate("targetCount", EOperator.IN))));
        assertEquals(List.of("TAGRULE_BAD_RANGE"), errors(track(predicate("targetCount", EOperator.BETWEEN, RuleValue.number(5), RuleValue.number(2)))));
        assertEquals(List.of("TAGRULE_OUT_OF_RANGE"), errors(track(predicate("targetCount", EOperator.EQ, RuleValue.number(256)))), "UINT8");
        assertEquals(List.of("TAGRULE_UNKNOWN_ENUM"), errors(track(predicate("targets[*].kind", EOperator.EQ, RuleValue.number(1)))),
                "enum values are chosen by name");
        assertEquals(List.of("TAGRULE_BAD_VALUE"), errors(track(predicate("targetCount", EOperator.EQ, RuleValue.number(1.5)))));
        assertEquals(List.of("TAGRULE_UNKNOWN_ENUM"), errors(track(predicate("targets[*].kind", EOperator.EQ, RuleValue.enumName("SPACE")))));
        assertEquals(List.of("TAGRULE_OUT_OF_RANGE"), errors(RuleCondition.fields(1, 1, predicate("flags.mode", EOperator.EQ, RuleValue.number(8)))),
                "a 3-bit field holds 0..7");
        assertEquals(List.of("TAGRULE_BAD_VALUE"), errors(RuleCondition.fields(1, 1, predicate("unitName", EOperator.EQ, RuleValue.number(1)))));
    }
}
