package com.pcapplayer.icd.profile;

import com.pcapplayer.pcapProcessing.diagnostics.Warning;

import java.util.List;

/**
 * The result of checking a time-fix profile.
 *
 * @param errors   problems that prevent saving
 * @param warnings suspicious but allowed choices
 */
public record ProfileCheck(List<Warning> errors, List<Warning> warnings) {
}
