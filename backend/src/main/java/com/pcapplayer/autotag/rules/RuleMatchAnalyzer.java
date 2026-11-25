package com.pcapplayer.autotag.rules;

import com.pcapplayer.icd.analysis.MessageAnalyzer;
import com.pcapplayer.icd.decode.DecodeStats;
import com.pcapplayer.icd.decode.DecodedMessage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Evaluates a run's rules on one file, in the same decoding pass as the message counts.
 * <p>
 * Keeps one counter per condition: every message is tested only against the conditions on its
 * opcode, and a condition's counter goes up when the message passes all its predicates. At
 * {@link #onEnd} a condition is true when its counter reaches its minimum, a rule matches when all
 * its conditions are true, and the file matches when at least one rule does. Decoding is never cut
 * short, because the full statistics are needed anyway.
 * <p>
 * One instance per file (not thread-safe); the {@link CompiledRules} are shared.
 */
public final class RuleMatchAnalyzer implements MessageAnalyzer {

    private static final long NONE = Long.MAX_VALUE;

    /** How often (in messages) the cancel flag is checked. */
    private static final int CANCEL_CHECK_EVERY = 1024;

    private final CompiledRules rules;
    private final BooleanSupplier cancelled;
    private final long[] counts;
    private final long[] firstNanos;
    private long seen;
    private RuleOutcome outcome;

    /** @param cancelled checked every {@value #CANCEL_CHECK_EVERY} messages; true stops the decode with a CancellationException */
    public RuleMatchAnalyzer(CompiledRules rules, BooleanSupplier cancelled) {
        this.rules = rules;
        this.cancelled = cancelled;
        this.counts = new long[rules.conditionCount()];
        this.firstNanos = new long[rules.conditionCount()];
        Arrays.fill(firstNanos, NONE);
    }

    public RuleMatchAnalyzer(CompiledRules rules) {
        this(rules, () -> false);
    }

    @Override
    public void onMessage(DecodedMessage message) {
        if (++seen % CANCEL_CHECK_EVERY == 0 && cancelled.getAsBoolean()) throw new CancellationException("Tagging cancelled");

        for (CompiledCondition condition : rules.conditionsFor(message.opcode())) {
            if (!condition.matches(message)) continue;
            int i = condition.index();
            counts[i]++;
            if (firstNanos[i] == NONE) firstNanos[i] = message.captureTsNanos();
        }
    }

    @Override
    public void onEnd(DecodeStats stats) {
        outcome = evaluate();
    }

    /** The result; available after {@link #onEnd}. */
    public RuleOutcome outcome() {
        if (outcome == null) outcome = evaluate();
        return outcome;
    }

    /** How many messages passed the condition at {@code index} (for tests and diagnostics). */
    public long count(int index) {
        return counts[index];
    }

    private RuleOutcome evaluate() {
        List<String> matched = new ArrayList<>();
        long first = NONE;

        for (CompiledRules.CompiledRule rule : rules.rules()) {
            boolean all = true;
            long ruleFirst = NONE;

            int[] indexes = rule.conditionIndexes();

            for (int i = 0; i < indexes.length; i++) {
                if (counts[indexes[i]] < rule.rule().conditions().get(i).minCount()) {
                    all = false;
                    break;
                }
                ruleFirst = Math.min(ruleFirst, firstNanos[indexes[i]]);
            }

            if (all) {
                matched.add(rule.rule().name());
                first = Math.min(first, ruleFirst);
            }
        }
        return new RuleOutcome(matched, first == NONE ? -1 : first);
    }
}
