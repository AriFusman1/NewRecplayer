package com.pcapplayer.icd.tree;

/** What a node of the ICD tree is. */
public enum ETreeNodeKind {
    HEADER,
    STRUCT,
    PRIMITIVE,
    ENUM,
    STRING,
    BITFIELD,
    BIT,
    PADDING
}
