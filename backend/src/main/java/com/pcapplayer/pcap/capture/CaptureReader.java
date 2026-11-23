package com.pcapplayer.pcap.capture;

import java.io.Closeable;
import java.io.IOException;

/** Streaming reader over the frames of a capture file. */
public interface CaptureReader extends Closeable {

    /** @return the next frame, or {@code null} at end of file */
    RawFrame next() throws IOException;

    /** "pcap" or "pcapng". */
    String format();

    /** True if the file ended in the middle of a record (the partial record is skipped). */
    boolean endedAbruptly();
}
