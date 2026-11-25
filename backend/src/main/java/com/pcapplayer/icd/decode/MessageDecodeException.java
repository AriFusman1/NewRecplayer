package com.pcapplayer.icd.decode;

/** The bytes of one message do not match its definition (too short, a bad array count, ...). */
public class MessageDecodeException extends Exception {

    public MessageDecodeException(String message) {
        super(message);
    }
}
