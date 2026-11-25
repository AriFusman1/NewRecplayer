package com.pcapplayer.playback.model;

import com.pcapplayer.icd.timefix.PatchPlan;

/**
 * Time fixing for one prepared record: the patch plan built from its decoded messages.
 *
 * @param icdId the ICD the plan was built with (for the replay log)
 */
public record TimeFix(String icdId, PatchPlan plan) {
}
