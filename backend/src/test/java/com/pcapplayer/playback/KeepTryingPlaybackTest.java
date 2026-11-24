package com.pcapplayer.playback;

import com.pcapplayer.pcapProcessing.script.EConnectionMode;
import com.pcapplayer.pcapProcessing.script.ReplayScript;
import com.pcapplayer.pcapProcessing.script.ScriptStep;
import com.pcapplayer.playback.transport.TcpConnectTransport;
import com.pcapplayer.playback.transport.TcpKeepTryingTransport;
import com.pcapplayer.playback.transport.Transport;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TCP-server replays that keep trying: they play on schedule with or without a listener, over real loopback sockets. */
class KeepTryingPlaybackTest {

    private static final long MS = 1_000_000;
    private static final long RETRY_MS = 100;

    /** Message k (one byte, value k) is due at k * stepMs; the range ends one step after the last. */
    private static ReplayScript script(int count, long stepMs) {
        List<ScriptStep> steps = new ArrayList<>();
        for (int messageNumber = 0; messageNumber < count; messageNumber++) {
            steps.add(new ScriptStep.Send(messageNumber * stepMs * MS, new byte[]{(byte) messageNumber}));
        }
        return new ReplayScript(EConnectionMode.TCP_SERVER, steps, count, count, count * stepMs * MS);
    }

    /** A port nothing listens on (taken from the OS, then released). */
    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    private static CompletableFuture<Void> play(ReplayScript script, Transport transport, ItemProgress progress) {
        return CompletableFuture.runAsync(() -> {
            Control control = new Control();
            control.bind(Thread.currentThread());

            try (transport) {
                ItemPlayer.play(script, transport, control, progress, state -> { }, (level, code, params) -> { });
            }
        });
    }

    /** Reads every byte of each accepted connection until it closes; counts connections. */
    private static CompletableFuture<Void> collect(ServerSocket server, ByteArrayOutputStream received, AtomicInteger connections,
                                                   int maxConnections) {
        return CompletableFuture.runAsync(() -> {
            try {
                for (int i = 0; i < maxConnections; i++) {
                    try (Socket client = server.accept(); InputStream in = client.getInputStream()) {
                        connections.incrementAndGet();
                        int b;
                        while ((b = in.read()) >= 0) {
                            synchronized (received) {
                                received.write(b);
                            }
                        }
                    }
                }
            } catch (IOException e) {
                // server closed by the test
            }
        });
    }

    @Test
    void nothingListeningStillPlaysToTheEndAndCountsEveryMessageAsNotDelivered() throws Exception {
        ReplayScript script = script(10, 30);
        RecordingConnectionEvents events = new RecordingConnectionEvents();
        ItemProgress progress = new ItemProgress(script.durationNanos(), script.messageCount());
        Transport transport = new TcpKeepTryingTransport("127.0.0.1", freePort(), 500, RETRY_MS, events);

        play(script, transport, progress).get(5, TimeUnit.SECONDS); // no CONNECT_FAILED

        assertEquals(0, progress.sentMessages());
        assertEquals(10, progress.missedMessages());
        assertEquals("not connected", events.lines.get(0));
        assertTrue(events.lines.stream().noneMatch("connected"::equals), events.lines.toString());
    }

    @Test
    void aServiceThatStartsListeningMidwayGetsTheMessagesFromThenOn() throws Exception {
        int port = freePort();
        ReplayScript script = script(30, 50); // 1.5 s
        RecordingConnectionEvents events = new RecordingConnectionEvents();
        ItemProgress progress = new ItemProgress(script.durationNanos(), script.messageCount());
        CompletableFuture<Void> run = play(script, new TcpKeepTryingTransport("127.0.0.1", port, 500, RETRY_MS, events), progress);

        Thread.sleep(500); // about 10 messages are due with nobody listening
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        AtomicInteger connections = new AtomicInteger();

        try (ServerSocket server = new ServerSocket(port)) {
            CompletableFuture<Void> reader = collect(server, received, connections, 1);
            run.get(5, TimeUnit.SECONDS);
            reader.get(5, TimeUnit.SECONDS); // the replay closed its connection at the end
        }

        byte[] bytes = received.toByteArray();
        assertEquals(30, progress.sentMessages() + progress.missedMessages());
        assertTrue(progress.missedMessages() >= 5 && progress.sentMessages() >= 10,
                "sent " + progress.sentMessages() + ", missed " + progress.missedMessages());
        assertEquals(progress.sentMessages(), bytes.length);
        // what arrived is the tail of the schedule, in order, without gaps
        for (int i = 0; i < bytes.length; i++) assertEquals(30 - bytes.length + i, bytes[i]);
        assertEquals(List.of("not connected", "connected"), events.lines);
    }

    @Test
    void aDroppedConnectionIsRetriedAndPlaybackContinues() throws Exception {
        int port = freePort();
        ReplayScript script = script(30, 50);
        RecordingConnectionEvents events = new RecordingConnectionEvents();
        ItemProgress progress = new ItemProgress(script.durationNanos(), script.messageCount());
        AtomicInteger connections = new AtomicInteger();
        ByteArrayOutputStream received = new ByteArrayOutputStream();

        try (ServerSocket server = new ServerSocket(port)) {
            // first connection: take 5 messages, then hang up; second connection: read to the end
            CompletableFuture<Void> service = CompletableFuture.runAsync(() -> {
                try {
                    try (Socket first = server.accept(); InputStream in = first.getInputStream()) {
                        connections.incrementAndGet();
                        for (int i = 0; i < 5; i++) received.write(in.read());
                    }
                    try (Socket second = server.accept(); InputStream in = second.getInputStream()) {
                        connections.incrementAndGet();
                        int b;
                        while ((b = in.read()) >= 0) received.write(b);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            CompletableFuture<Void> run = play(script, new TcpKeepTryingTransport("127.0.0.1", port, 500, RETRY_MS, events), progress);
            run.get(5, TimeUnit.SECONDS); // no CONNECTION_LOST
            service.get(5, TimeUnit.SECONDS);
        }

        assertEquals(2, connections.get());
        assertEquals(30, progress.sentMessages() + progress.missedMessages());
        assertTrue(progress.sentMessages() > 15, "sent " + progress.sentMessages());
        assertEquals(List.of("connected", "lost", "connected"), events.lines);
    }

    @Test
    void theStrictTransportStillFailsWhenNothingListens() throws Exception {
        ReplayScript script = script(3, 10);
        ItemProgress progress = new ItemProgress(script.durationNanos(), script.messageCount());
        Transport strict = new TcpConnectTransport("127.0.0.1", freePort(), 500);

        Exception e = assertThrows(Exception.class, () -> play(script, strict, progress).get(5, TimeUnit.SECONDS));
        assertTrue(e.getCause() instanceof ReplayFailure failure && failure.code().equals("CONNECT_FAILED"), String.valueOf(e.getCause()));
    }
}
