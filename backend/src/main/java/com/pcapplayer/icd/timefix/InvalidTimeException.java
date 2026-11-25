package com.pcapplayer.icd.timefix;

/** A field's value cannot be a time (not a number, or so large the instant overflows). */
public class InvalidTimeException extends Exception {

    public InvalidTimeException(String message) {
        super(message);
    }
}
