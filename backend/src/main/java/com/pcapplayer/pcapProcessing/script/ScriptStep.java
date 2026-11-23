package com.pcapplayer.pcapProcessing.script;

/** One step of a replay. Offsets are nanoseconds from the range start. */
public sealed interface ScriptStep {

    long offsetNanos();

    /** Send these bytes (one UDP datagram, or one TCP write). */
    record Send(long offsetNanos, byte[] data) implements ScriptStep {
    }

    /**
     * The recording shows the TCP connection closing at {@code offsetNanos} and a new one opening at
     * {@code connectOffsetNanos} (≥ offsetNanos).
     */
    record Reconnect(long offsetNanos, long connectOffsetNanos) implements ScriptStep {
    }

    /** Marker only (nothing is sent): the capture is missing {@code bytes} bytes of the stream here. */
    record Gap(long offsetNanos, long bytes) implements ScriptStep {
    }
}
