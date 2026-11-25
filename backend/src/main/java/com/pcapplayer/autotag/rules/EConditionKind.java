package com.pcapplayer.autotag.rules;

/** The two kinds of rule condition. */
public enum EConditionKind {

    /** "Message type M appears at least N times." */
    COUNT,

    /** "At least N messages of type M satisfy every field predicate." */
    FIELDS
}
