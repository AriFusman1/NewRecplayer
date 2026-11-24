package com.pcapplayer.playback;

import com.pcapplayer.playback.transport.TcpKeepTryingTransport;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records the connection changes a session log would show: "connected", "not connected", "lost". */
final class RecordingConnectionEvents implements TcpKeepTryingTransport.Events {

    final List<String> lines = new CopyOnWriteArrayList<>();

    @Override
    public void connected() {
        lines.add("connected");
    }

    @Override
    public void notConnected(String reason, boolean wasConnected) {
        lines.add(wasConnected ? "lost" : "not connected");
    }
}
