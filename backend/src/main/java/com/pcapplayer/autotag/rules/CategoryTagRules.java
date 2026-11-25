package com.pcapplayer.autotag.rules;

import java.util.List;

/**
 * The saved rules of one category ({@code tagrules/{categoryId}.json}).
 *
 * @param icdId the category's ICD when the rules were last saved; a different current ICD is reported
 */
public record CategoryTagRules(String categoryId, String icdId, long updatedAtMs, List<TagRule> rules) {
}
