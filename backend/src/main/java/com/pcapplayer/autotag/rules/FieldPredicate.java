package com.pcapplayer.autotag.rules;

import java.util.List;

/**
 * {@code path operator value(s)}, for example {@code targets[*].kind IN [AIR, SURFACE]}.
 *
 * @param path a path of the ICD tree; {@code [*]} stands for every element of an array
 */
public record FieldPredicate(String path, EOperator op, List<RuleValue> values) {
}
