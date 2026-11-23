package com.pcapplayer.pcap.capture;

import java.io.IOException;

/** The input is not a readable pcap / pcapng file. */
public class InvalidCaptureException extends IOException {

    public InvalidCaptureException(String message) {
        super(message);
    }
}
