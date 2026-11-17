package com.pcapplayer.capture;

import java.io.IOException;
import java.nio.file.Path;

/** Opens a capture file (pcap or pcapng) through libpcap, via pcap4j. */
public final class CaptureReaders {

    private CaptureReaders() {
    }

    public static CaptureReader open(Path file) throws IOException {
        return new Pcap4jCaptureReader(file);
    }
}
