package com.pcapplayer.playback;

import com.pcapplayer.pcapProcessing.script.EConnectionMode;
import com.pcapplayer.pcapProcessing.script.Histogram;
import com.pcapplayer.pcapProcessing.script.ReplayScript;
import com.pcapplayer.pcapProcessing.script.ScriptStep;
import com.pcapplayer.playback.model.HistoryEntry;
import com.pcapplayer.playback.model.ItemRequest;
import com.pcapplayer.playback.model.ReplayListener;
import com.pcapplayer.playback.model.ScriptSource;
import com.pcapplayer.playback.model.SessionView;
import com.pcapplayer.playback.model.Target;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "Play together": all-or-nothing reservation and a common start. */
class ReplayGroupTest {

    private static final long MS = 1_000_000;
    private static final String LOCALHOST = "127.0.0.1";

    /** Records "slow-*" (prepared in 400 ms), "bad-*" (cannot be prepared), "listen-*" (TCP client) and others (UDP); every script sends at 0, 100 and 200 ms. */
    private static final class FakeSource implements ScriptSource {
        @Override
        public RecordInfo describe(String recordId) {
            EConnectionMode mode = recordId.startsWith("listen") ? EConnectionMode.TCP_CLIENT : EConnectionMode.UDP_SERVER;
            return new RecordInfo(recordId, recordId, "category", mode);
        }

        @Override
        public Prepared prepare(String recordId) {
            if (recordId.startsWith("slow")) sleep(400);
            if (recordId.startsWith("bad")) throw new ReplayFailure("PREPARE_FAILED", Map.of(), "cannot read");
            List<ScriptStep> steps = List.of(new ScriptStep.Send(0, new byte[]{1}),
                    new ScriptStep.Send(100 * MS, new byte[]{2}), new ScriptStep.Send(200 * MS, new byte[]{3}));
            ReplayScript script = new ReplayScript(describe(recordId).mode(), steps, 3, 3, 250 * MS);
            return new Prepared(describe(recordId), 0, script, Histogram.of(script, 10));
        }
    }

    private static final class Listener implements ReplayListener {
        final List<HistoryEntry> finished = new ArrayList<>();

        @Override
        public void sessionsChanged() {
        }

        @Override
        public void notice(String level, String code, Map<String, Object> params, String sessionId, String startedBy) {
        }

        @Override
        public synchronized void itemFinished(HistoryEntry entry) {
            finished.add(entry);
        }

        synchronized List<String> results() {
            return finished.stream().map(HistoryEntry::result).toList();
        }
    }

    private final Listener listener = new Listener();
    private final ReplayEngine engine = new ReplayEngine(new FakeSource(), listener, new ReplayEngine.Settings(1000));
    private final List<DatagramSocket> receivers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        engine.stopAll("test");
        receivers.forEach(DatagramSocket::close);
    }

    private DatagramSocket receiver() throws Exception {
        DatagramSocket socket = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        socket.setSoTimeout(3000);
        receivers.add(socket);
        return socket;
    }

    private static ItemRequest udp(String recordId, DatagramSocket to) {
        return new ItemRequest(recordId, new Target(LOCALHOST, to.getLocalPort()), 0);
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void membersStartTogetherEvenWhenOneTakesLongerToPrepare() throws Exception {
        List<DatagramSocket> sockets = List.of(receiver(), receiver(), receiver());
        List<SessionView> views = engine.startGroup(List.of(
                udp("udp-a", sockets.get(0)), udp("slow-b", sockets.get(1)), udp("udp-c", sockets.get(2))), "dana");

        assertEquals(3, views.size());
        Set<String> groupIds = views.stream().map(view -> view.group().id()).collect(Collectors.toSet());
        assertEquals(1, groupIds.size(), "all sessions share one group");
        assertTrue(views.stream().allMatch(view -> view.group().size() == 3 && !view.playlist()));

        // first datagram of each member: without the gate, a and c would be ~400 ms ahead of b
        Map<Integer, Long> firstArrival = new ConcurrentHashMap<>();
        for (int index = 0; index < sockets.size(); index++) {
            sockets.get(index).receive(new DatagramPacket(new byte[8], 8));
            firstArrival.put(index, System.nanoTime());
        }
        long spread = firstArrival.values().stream().mapToLong(Long::longValue).max().orElseThrow()
                - firstArrival.values().stream().mapToLong(Long::longValue).min().orElseThrow();
        assertTrue(spread < 100 * MS, "members started " + spread / MS + " ms apart");
    }

    @Test
    void nothingStartsWhenOneTargetIsBusy() throws Exception {
        DatagramSocket free = receiver();
        DatagramSocket busy = receiver();
        engine.start(List.of(udp("udp-running", busy)), false, "other");

        ReplayFailure failure = assertThrows(ReplayFailure.class,
                () -> engine.startGroup(List.of(udp("udp-a", free), udp("udp-b", busy)), "dana"));
        assertEquals("TARGET_BUSY", failure.code());
        assertEquals("udp-b", failure.params().get("record"));
        assertEquals(1, engine.views().size(), "the group's first member must not have started");

        // the first member's target was released again
        assertEquals(1, engine.startGroup(List.of(udp("udp-a", free)), "dana").size());
    }

    @Test
    void listenPortIsReleasedWhenTheGroupIsRefused() throws Exception {
        int listenPort = freePort();
        DatagramSocket busy = receiver();
        engine.start(List.of(udp("udp-running", busy)), false, "other");

        assertThrows(ReplayFailure.class, () -> engine.startGroup(List.of(
                new ItemRequest("listen-a", new Target(null, listenPort), 0), udp("udp-b", busy)), "dana"));
        try (ServerSocket again = new ServerSocket(listenPort)) {
            assertEquals(listenPort, again.getLocalPort());
        }
    }

    @Test
    void theSameTargetTwiceIsRefused() throws Exception {
        DatagramSocket socket = receiver();
        ReplayFailure failure = assertThrows(ReplayFailure.class,
                () -> engine.startGroup(List.of(udp("udp-a", socket), udp("udp-b", socket)), "dana"));
        assertEquals("DUPLICATE_TARGET", failure.code());
        assertEquals("udp-b", failure.params().get("record"));
        assertTrue(engine.views().isEmpty());
    }

    @Test
    void groupSizeIsBetweenOneAndTen() throws Exception {
        assertEquals("GROUP_SIZE", assertThrows(ReplayFailure.class, () -> engine.startGroup(List.of(), "dana")).code());
        List<ItemRequest> eleven = new ArrayList<>();
        for (int index = 0; index < 11; index++) eleven.add(udp("udp-" + index, receiver()));
        assertEquals("GROUP_SIZE", assertThrows(ReplayFailure.class, () -> engine.startGroup(eleven, "dana")).code());
        assertTrue(engine.views().isEmpty());
    }

    @Test
    void aMemberThatFailsWhilePreparingDoesNotHoldTheOthersBack() throws Exception {
        DatagramSocket socket = receiver();
        engine.startGroup(List.of(udp("udp-a", socket), udp("bad-b", receiver())), "dana");

        socket.receive(new DatagramPacket(new byte[8], 8)); // times out (3 s) if the gate never opens
        waitUntil(() -> listener.results().size() == 2);
        assertEquals(Set.of("COMPLETED", "FAILED"), Set.copyOf(listener.results()));
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < end, "timed out");
            sleep(10);
        }
    }
}
