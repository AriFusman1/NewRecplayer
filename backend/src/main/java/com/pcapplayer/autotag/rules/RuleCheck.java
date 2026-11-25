package com.pcapplayer.autotag.rules;

import com.pcapplayer.pcapProcessing.diagnostics.Warning;

import java.util.List;

/**
 * The result of checking a rule against an ICD.
 *
 * @param rule   the rule with enum numbers filled in from the names; use it when there are no errors
 * @param errors every problem found (empty when the rule is valid)
 */
public record RuleCheck(TagRule rule, List<Warning> errors) {

    public boolean valid() {
        return errors.isEmpty();
    }
}
