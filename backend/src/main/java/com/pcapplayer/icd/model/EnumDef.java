package com.pcapplayer.icd.model;

import java.util.SortedMap;

/**
 * An enumeration stored as an integer.
 *
 * @param name   the enum's name in the ICD
 * @param type   the underlying integer type
 * @param values value → name, sorted by value
 */
public record EnumDef(String name, EFieldType type, SortedMap<Long, String> values) {
}
