package com.pcapplayer.autotag.rules;

import java.util.List;

/**
 * What the rules said about one file.
 *
 * @param matchedRules     names of the rules the file matched, in rule order
 * @param firstMatchNanos  capture time (epoch nanos) of the earliest message that counted for a
 *                         condition of a matched rule; -1 when nothing matched
 */
public record RuleOutcome(List<String> matchedRules, long firstMatchNanos) {

    public boolean matched() {
        return !matchedRules.isEmpty();
    }
}
