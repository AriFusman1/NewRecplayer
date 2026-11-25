package com.pcapplayer.icd.model;

/**
 * Makes a field repeat. Exactly one of the two is set.
 *
 * @param length      fixed number of elements, or 0 when {@code lengthField} is used
 * @param lengthField path of an integer field that holds the number of elements; a plain name means a
 *                    field earlier in the same struct, {@code header.x} a header field, and a dotted path
 *                    a field from the message root. Null for a fixed length.
 */
public record ArrayDef(int length, String lengthField) {

    public static ArrayDef fixed(int length) {
        return new ArrayDef(length, null);
    }

    public static ArrayDef counted(String lengthField) {
        return new ArrayDef(0, lengthField);
    }

    public boolean variable() {
        return lengthField != null;
    }
}
