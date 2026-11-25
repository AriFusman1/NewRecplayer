package com.pcapplayer.playback;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

/** Keeps the messages logged to the logger it is added to. */
final class CapturingLogHandler extends Handler {

    private final List<String> messages = new ArrayList<>();

    @Override
    public synchronized void publish(LogRecord record) {
        messages.add(record.getLevel() + " " + record.getMessage());
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }

    synchronized List<String> messages() {
        return new ArrayList<>(messages);
    }
}
