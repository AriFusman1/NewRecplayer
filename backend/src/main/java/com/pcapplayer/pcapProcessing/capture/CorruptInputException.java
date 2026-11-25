package com.pcapplayer.pcapProcessing.capture;

import java.io.IOException;

/** The input could not be read to the end: a damaged or cut-off gzip stream. */
public class CorruptInputException extends IOException {

    public CorruptInputException(String message, Throwable cause) {
        super(message, cause);
    }
}
