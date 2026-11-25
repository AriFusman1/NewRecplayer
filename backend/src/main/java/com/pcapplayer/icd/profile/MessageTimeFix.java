package com.pcapplayer.icd.profile;

import java.util.List;

/**
 * How to fix the times of one message type.
 *
 * @param base   the field whose original time is the reference; it is rewritten to the send time
 * @param fields the other time fields; each keeps its original distance from the base
 */
public record MessageTimeFix(long opcode, TimeFieldRef base, List<TimeFieldRef> fields) {
}
