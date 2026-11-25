package com.pcapplayer.icd.timefix;

/**
 * A time encoded for one field, ready to write.
 *
 * @param raw     the bits to store: the integer value (two's complement), or the IEEE bits of a float
 * @param clamped true if the value did not fit the field and was set to the nearest value that does
 */
public record EncodedValue(long raw, boolean clamped) {
}
