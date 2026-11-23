package com.pcapplayer.replay;

import com.pcapplayer.pcap.script.EConnectionMode;
import com.pcapplayer.pcap.script.ReplayScript;
import com.pcapplayer.pcap.script.ScriptStep;
import com.pcapplayer.replay.transport.Transport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemPlayerSeekTest {

    private static final long MS = 1_000_000;

    /** Records the first byte of every send; never touches the network. */
    private static class FakeTransport implements Transport {
        final List<Integer> sent = new CopyOnWriteArrayList<>();
        final AtomicInteger reopens = new AtomicInteger();

        @Override public void open(Control control) { }
        @Override public void send(byte[] data, Control control) { sent.add((int) data[0]); }
        @Override public void closeConnection() { }
        @Override public void reopen(Control control) { reopens.incrementAndGet(); }
        @Override public boolean waitsForPeer() { return false; }
        @Override public void close() { }
    }

    /** Message k is sent at k*100 ms; the range is {@code count*100} ms long. */
    private static ReplayScript script(int count, List<ScriptStep> extra) {
        List<ScriptStep> steps = new ArrayList<>();
        for (int messageNumber = 0; messageNumber < count; messageNumber++) steps.add(new ScriptStep.Send(messageNumber * 100 * MS, new byte[]{(byte) messageNumber}));
        steps.addAll(extra);
        steps.sort(java.util.Comparator.comparingLong(ScriptStep::offsetNanos));
        return new ReplayScript(EConnectionMode.UDP_SERVER, steps, count, count, count * 100L * MS);
    }

    private static CompletableFuture<Void> start(ReplayScript script, FakeTransport transport, Control control, ItemProgress progress) {
        return CompletableFuture.runAsync(() -> {
            control.bind(Thread.currentThread());
            ItemPlayer.play(script, transport, control, progress, state -> { }, (lvl, code, params) -> { });
        });
    }

    private static void waitFor(java.util.function.BooleanSupplier cond) throws InterruptedException {
        long end = System.nanoTime() + 5_000 * MS;
        while (!cond.getAsBoolean()) {
            assertTrue(System.nanoTime() < end, "timed out");
            Thread.sleep(5);
        }
    }

    @Test
    void forwardSeekSkipsMessagesInBetween() throws Exception {
        ReplayScript script = script(50, List.of());
        FakeTransport transport = new FakeTransport();
        Control control = new Control();
        ItemProgress progress = new ItemProgress(script.durationNanos(), script.messageCount());
        CompletableFuture<Void> run = start(script, transport, control, progress);

        waitFor(() -> transport.sent.contains(1));
        control.seek(4_500 * MS); // jump to 4.5 s: next message is #45
        run.get(5, TimeUnit.SECONDS);

        List<Integer> sent = new ArrayList<>(transport.sent);
        assertTrue(sent.contains(45) && sent.contains(49));
        assertTrue(sent.stream().noneMatch(messageNumber -> messageNumber >= 10 && messageNumber < 45), "skipped messages were sent: " + sent);
        // counters reflect the position: everything before 4.5 s counts as passed
        assertEquals(50, progress.sentMessages());
    }

    @Test
    void backwardSeekReplaysFromEarlierPoint() throws Exception {
        ReplayScript script = script(10, List.of());
        FakeTransport transport = new FakeTransport();
        Control control = new Control();
        ItemProgress progress = new ItemProgress(script.durationNanos(), script.messageCount());
        CompletableFuture<Void> run = start(script, transport, control, progress);

        waitFor(() -> transport.sent.contains(6));
        control.seek(200 * MS);
        run.get(5, TimeUnit.SECONDS);

        List<Integer> sent = new ArrayList<>(transport.sent);
        int jump = sent.lastIndexOf(2);
        assertTrue(jump > sent.indexOf(6), "message #2 was not resent after the jump: " + sent);
        assertEquals(List.of(2, 3, 4, 5, 6, 7, 8, 9), sent.subList(jump, sent.size()));
    }

    @Test
    void seekAfterLastMessageStillWorks() throws Exception {
        ReplayScript script = script(3, List.of());
        FakeTransport transport = new FakeTransport();
        Control control = new Control();
        ItemProgress progress = new ItemProgress(script.durationNanos(), script.messageCount());
        CompletableFuture<Void> run = start(script, transport, control, progress);

        waitFor(() -> transport.sent.contains(2)); // now waiting out the end of the range
        control.seek(0);
        run.get(5, TimeUnit.SECONDS);
        assertEquals(List.of(0, 1, 2, 0, 1, 2), new ArrayList<>(transport.sent));
    }

    @Test
    void jumpedOverReconnectIsIgnored() throws Exception {
        ReplayScript script = script(20, List.of(new ScriptStep.Reconnect(1_000 * MS + 1, 1_000 * MS + 2)));
        FakeTransport transport = new FakeTransport();
        Control control = new Control();
        ItemProgress progress = new ItemProgress(script.durationNanos(), script.messageCount());
        CompletableFuture<Void> run = start(script, transport, control, progress);

        waitFor(() -> transport.sent.contains(0));
        control.seek(1_500 * MS);
        run.get(5, TimeUnit.SECONDS);
        assertEquals(0, transport.reopens.get());
    }
}
