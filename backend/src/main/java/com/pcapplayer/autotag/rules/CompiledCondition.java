package com.pcapplayer.autotag.rules;

import com.pcapplayer.icd.decode.DecodedMessage;

import java.util.List;

/**
 * A condition ready to test messages of its opcode.
 * <p>
 * The predicates are checked one by one (AND). Each is checked on its own: two predicates on the same
 * array do <b>not</b> have to be true for the same element. {@code targets[*].kind = AIR} and
 * {@code targets[*].alt > 1000} match a message with an AIR target and a (possibly different) target
 * above 1000.
 *
 * @param index position of the condition in {@link CompiledRules}, the index of its counter
 */
record CompiledCondition(int index, RuleCondition source, List<CompiledPredicate> predicates) {

    boolean matches(DecodedMessage message) {
        for (CompiledPredicate predicate : predicates) {
            if (!predicate.matches(message)) return false;
        }
        return true; // a COUNT condition has no predicates: every message of the opcode counts
    }
}
