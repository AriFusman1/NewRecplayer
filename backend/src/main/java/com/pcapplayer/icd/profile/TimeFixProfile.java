package com.pcapplayer.icd.profile;

import java.util.List;

/**
 * The time-fix settings of one category: which fields of which messages to rewrite at send time.
 *
 * @param icdId the ICD the paths refer to (the category's ICD when the profile was saved)
 */
public record TimeFixProfile(String categoryId, String icdId, long updatedAtMs, List<MessageTimeFix> messages) {
}
