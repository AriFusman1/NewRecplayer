package com.pcapplayer.autotag.rules;

import com.pcapplayer.icd.decode.DecodedMessage;
import com.pcapplayer.icd.decode.ScriptDecoder;
import com.pcapplayer.icd.model.EFieldType;
import com.pcapplayer.icd.tree.IcdTree;
import com.pcapplayer.icd.tree.IcdTreeBuilder;
import com.pcapplayer.pcapProcessing.script.EConnectionMode;
import com.pcapplayer.pcapProcessing.script.ReplayScript;
import com.pcapplayer.pcapProcessing.script.ScriptFlow;
import com.pcapplayer.pcapProcessing.script.ScriptStep;
import com.pcapplayer.testSupport.SampleIcdMessages;
import com.pcapplayer.testSupport.SampleIcdMessages.TargetValues;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.pcapplayer.autotag.rules.Values.enumValue;
import static com.pcapplayer.autotag.rules.Values.integer;
import static com.pcapplayer.autotag.rules.Values.message;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rule evaluation over decoded messages of the sample ICD (Status = opcode 1, Track = opcode 2). */
class RuleMatchAnalyzerTest {

    private static final IcdTree TREE = IcdTreeBuilder.of(SampleIcdMessages.icd());
    private static final byte AIR = 1;
    private static final byte SURFACE = 2;

    private static RuleOutcome run(List<TagRule> rules, List<DecodedMessage> messages) {
        List<TagRule> checked = new ArrayList<>();

        for (TagRule rule : rules) {
            RuleCheck check = RuleValidator.check(rule, TREE);
            assertTrue(check.valid(), () -> "invalid test rule: " + check.errors());
            checked.add(check.rule());
        }
        RuleMatchAnalyzer analyzer = new RuleMatchAnalyzer(CompiledRules.compile(checked, TREE, 1e-6));
        messages.forEach(analyzer::onMessage);
        analyzer.onEnd(null);
        return analyzer.outcome();
    }

    private static TagRule rule(String name, RuleCondition... conditions) {
        return new TagRule("id-" + name, name, List.of(conditions));
    }

    private static FieldPredicate predicate(String path, EOperator op, RuleValue... values) {
        return new FieldPredicate(path, op, List.of(values));
    }

    /** A Track message whose targets have these kinds and altitudes. */
    private static DecodedMessage track(long tsNanos, byte[] kinds, int[] alts) {
        List<Object> fields = new ArrayList<>(List.of("targetCount", integer(EFieldType.UINT8, kinds.length)));

        for (int i = 0; i < kinds.length; i++) {
            fields.add("targets[" + i + "].kind");
            fields.add(enumValue(kinds[i], kinds[i] == AIR ? "AIR" : "SURFACE"));
            fields.add("targets[" + i + "].pos.alt");
            fields.add(integer(EFieldType.INT16, alts[i]));
        }
        return message(2, tsNanos, fields.toArray());
    }

    private static DecodedMessage status(long tsNanos) {
        return message(1, tsNanos, "status", enumValue(2, "OPERATIONAL"));
    }

    @Test
    void countConditionNeedsTheMinimum() {
        TagRule twoStatuses = rule("two statuses", RuleCondition.count(1, 2));

        assertFalse(run(List.of(twoStatuses), List.of(status(1))).matched());
        assertTrue(run(List.of(twoStatuses), List.of(status(1), status(2))).matched());
        assertTrue(run(List.of(twoStatuses), List.of(status(1), status(2), status(3))).matched());
    }

    @Test
    void fieldConditionCountsOnlyMessagesPassingEveryPredicate() {
        TagRule highAir = rule("high air", RuleCondition.fields(2, 2,
                predicate("targets[*].kind", EOperator.EQ, RuleValue.enumName("AIR")),
                predicate("targetCount", EOperator.GE, RuleValue.number(2))));
        List<DecodedMessage> oneQualifying = List.of(
                track(1, new byte[]{AIR, SURFACE}, new int[]{100, 200}),   // qualifies
                track(2, new byte[]{AIR}, new int[]{100}),                // only one target
                track(3, new byte[]{SURFACE, SURFACE}, new int[]{1, 2})); // no AIR

        assertFalse(run(List.of(highAir), oneQualifying).matched());

        List<DecodedMessage> twoQualifying = new ArrayList<>(oneQualifying);
        twoQualifying.add(track(4, new byte[]{SURFACE, AIR}, new int[]{5, 6}));
        assertTrue(run(List.of(highAir), twoQualifying).matched());
    }

    @Test
    void arrayPredicatesMatchAnyElementIndependently() {
        // "some target is AIR" AND "some target is above 1000": not required to be the same target
        TagRule rule = rule("air and high", RuleCondition.fields(2, 1,
                predicate("targets[*].kind", EOperator.EQ, RuleValue.enumName("AIR")),
                predicate("targets[*].pos.alt", EOperator.GT, RuleValue.number(1000))));

        assertTrue(run(List.of(rule), List.of(track(1, new byte[]{AIR, SURFACE}, new int[]{10, 5000}))).matched(),
                "the AIR target is low, the high target is SURFACE: still a match");
        assertFalse(run(List.of(rule), List.of(track(1, new byte[]{SURFACE, SURFACE}, new int[]{10, 5000}))).matched());
        assertFalse(run(List.of(rule), List.of(track(1, new byte[]{}, new int[]{}))).matched(), "no elements, no match");
    }

    @Test
    void allConditionsOfARuleMustHold() {
        TagRule both = rule("both", RuleCondition.count(1, 1), RuleCondition.count(2, 1));

        assertFalse(run(List.of(both), List.of(status(1))).matched());
        assertTrue(run(List.of(both), List.of(status(1), track(2, new byte[]{AIR}, new int[]{1}))).matched());
    }

    @Test
    void severalRulesReportEveryRuleMatched() {
        TagRule statuses = rule("statuses", RuleCondition.count(1, 1));
        TagRule tracks = rule("tracks", RuleCondition.count(2, 1));
        TagRule manyTracks = rule("many tracks", RuleCondition.count(2, 5));

        RuleOutcome outcome = run(List.of(statuses, tracks, manyTracks),
                List.of(status(1), track(2, new byte[]{AIR}, new int[]{1})));

        assertEquals(List.of("statuses", "tracks"), outcome.matchedRules());
    }

    @Test
    void firstMatchIsTheEarliestMessageThatCountedForAMatchedRule() {
        TagRule tracks = rule("tracks", RuleCondition.count(2, 1));
        TagRule manyStatuses = rule("many statuses", RuleCondition.count(1, 10)); // does not match

        RuleOutcome outcome = run(List.of(tracks, manyStatuses),
                List.of(status(100), track(500, new byte[]{AIR}, new int[]{1}), track(900, new byte[]{AIR}, new int[]{1})));

        assertEquals(500, outcome.firstMatchNanos(), "the earlier status counts for a rule that did not match");
        assertEquals(-1, run(List.of(manyStatuses), List.of(status(1))).firstMatchNanos());
    }

    @Test
    void worksOnMessagesFromTheRealDecoder() {
        byte[] trackBytes = SampleIcdMessages.track(1, 0, 0, 0, 0,
                new TargetValues(7, 1f, 2f, (short) 3000, 0, SURFACE), new TargetValues(8, 1f, 2f, (short) 10, 0, AIR));
        byte[] statusBytes = SampleIcdMessages.status(2, 0, 0);
        List<ScriptStep> steps = List.of(new ScriptStep.Send(0, trackBytes, 0), new ScriptStep.Send(1_000_000, statusBytes, 0));
        ReplayScript script = new ReplayScript(EConnectionMode.TCP_SERVER, steps, 2, 0, 2_000_000,
                List.of(new ScriptFlow(0, true, 1, 1000, 2, 2000)));
        TagRule rule = rule("decoded", RuleCondition.fields(2, 1,
                predicate("targets[*].kind", EOperator.EQ, RuleValue.enumName("AIR")),
                predicate("targets[*].pos.alt", EOperator.GE, RuleValue.number(3000))),
                RuleCondition.fields(1, 1,
                        predicate("unitName", EOperator.STARTS_WITH, RuleValue.text("UNIT")),
                        predicate("healthy", EOperator.EQ, RuleValue.bool(true)),
                        predicate("flags.mode", EOperator.EQ, RuleValue.number(5)),
                        predicate("status", EOperator.IN, RuleValue.enumName("OPERATIONAL"), RuleValue.enumName("FAULT"))));
        RuleCheck check = RuleValidator.check(rule, TREE);
        assertTrue(check.valid(), () -> check.errors().toString());
        RuleMatchAnalyzer analyzer = new RuleMatchAnalyzer(CompiledRules.compile(List.of(check.rule()), TREE, 1e-6));

        ScriptDecoder.decode(script, 0, SampleIcdMessages.icd(), analyzer);

        assertEquals(List.of("decoded"), analyzer.outcome().matchedRules());
        assertEquals(1, analyzer.count(0));
        assertEquals(1, analyzer.count(1));
    }
}
