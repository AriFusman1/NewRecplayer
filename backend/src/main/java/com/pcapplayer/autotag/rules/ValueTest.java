package com.pcapplayer.autotag.rules;

import com.pcapplayer.icd.decode.FieldValue;

/** One predicate's operator and values, compiled for one field type: tests a single decoded value. */
@FunctionalInterface
public interface ValueTest {

    boolean test(FieldValue value);
}
