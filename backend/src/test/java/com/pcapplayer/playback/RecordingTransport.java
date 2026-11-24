package com.pcapplayer.playback;

import com.pcapplayer.playback.transport.Transport;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Keeps a copy of every send, whole; never touches the network. */
final class RecordingTransport implements Transport {

    final List<byte[]> sent = new CopyOnWriteArrayList<>();

    @Override public void open(Control control) { }
    @Override public void send(byte[] data, Control control) { sent.add(data.clone()); }
    @Override public void closeConnection() { }
    @Override public void reopen(Control control) { }
    @Override public boolean waitsForPeer() { return false; }
    @Override public void close() { }
}
