package com.pcapplayer.pcapProcessing.script;

/** One step of a replay. Offsets are nanoseconds from the range start. */
public sealed interface ScriptStep {

    long offsetNanos();

    /**
     * Send these bytes (one UDP datagram, or one TCP write).
     *
     * @param flowId the recorded flow the bytes came from, an index into {@link ReplayScript#flows()}
     */
    record Send(long offsetNanos, byte[] data, int flowId) implements ScriptStep {

        /** A send from flow 0 (scripts with a single flow, and tests). */
        public Send(long offsetNanos, byte[] data) {
            this(offsetNanos, data, 0);
        }
    }

    /**
     * The recording shows the TCP connection closing at {@code offsetNanos} and a new one opening at
     * {@code connectOffsetNanos} (≥ offsetNanos).
     */
    record Reconnect(long offsetNanos, long connectOffsetNanos) implements ScriptStep {
    }

    /**
     * Marker only (nothing is sent): the capture is missing {@code bytes} bytes of flow {@code flowId}'s
     * stream here.
     */
    record Gap(long offsetNanos, long bytes, int flowId) implements ScriptStep {

        public Gap(long offsetNanos, long bytes) {
            this(offsetNanos, bytes, 0);
        }
    }
}
