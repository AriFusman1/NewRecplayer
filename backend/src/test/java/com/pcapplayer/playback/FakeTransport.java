package com.pcapplayer.playback;

import com.pcapplayer.playback.transport.Transport;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Records the first byte of every send; never touches the network. */
final class FakeTransport implements Transport {

    final List<Integer> sent = new CopyOnWriteArrayList<>();
    final AtomicInteger reopens = new AtomicInteger();

    @Override public void open(Control control) { }
    @Override public void send(byte[] data, Control control) { sent.add((int) data[0]); }
    @Override public void closeConnection() { }
    @Override public void reopen(Control control) { reopens.incrementAndGet(); }
    @Override public boolean waitsForPeer() { return false; }
    @Override public void close() { }
}
