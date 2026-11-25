package com.pcapplayer.autotag.rules;

import java.util.List;

/** A named rule: a file matches it when all of its conditions are true (AND). */
public record TagRule(String id, String name, List<RuleCondition> conditions) {
}
