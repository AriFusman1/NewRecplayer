package com.pcapplayer.icd.profile;

import com.pcapplayer.icd.timefix.ETimeKind;

/**
 * A time field of a message and how it stores time.
 *
 * @param path the field's path; {@code [*]} stands for every element of an array ({@code targets[*].seenGps})
 */
public record TimeFieldRef(String path, ETimeKind kind) {
}
