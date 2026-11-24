package com.pcapplayer.playback;

import com.pcapplayer.playback.model.ItemRequest;
import com.pcapplayer.playback.model.Target;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.pcapplayer.playback.ReplayTestKit.LOCALHOST;
import static com.pcapplayer.playback.ReplayTestKit.freePort;
import static com.pcapplayer.playback.ReplayTestKit.listen;
import static com.pcapplayer.playback.ReplayTestKit.udp;
import static com.pcapplayer.playback.ReplayTestKit.udpReceiver;
import static com.pcapplayer.playback.ReplayTestKit.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The global limit on running replays, for all users together. */
class ActiveReplaysTest {

    private static final int MAX = 10;

    private final ReplayTestKit.RecordingListener listener = new ReplayTestKit.RecordingListener();
    /** Long connect timeout: groups with a TCP client keep their slots while waiting. */
    private final ReplayEngine engine = ReplayTestKit.engine(listener, MAX, 60_000);
    private final List<AutoCloseable> cleanup = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        engine.stopAll("test");
        waitUntil("all replays ended", () -> engine.activeReplays() == 0);
        for (AutoCloseable closeable : cleanup) closeable.close();
    }

    /** A group of {@code count} TCP clients: they wait for a connection, so they stay active. */
    private List<ItemRequest> waitingGroup(String prefix, int count) throws Exception {
        List<ItemRequest> items = new ArrayList<>();
        for (int index = 0; index < count; index++) items.add(listen("listen-" + prefix + index, freePort()));
        return items;
    }

    @Test
    void twoUsersStartingAtTheSameMomentCannotPassTheLimit() throws Exception {
        List<ItemRequest> dana = waitingGroup("dana", 6);
        List<ItemRequest> omer = waitingGroup("omer", 6);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService users = Executors.newFixedThreadPool(2);
        Callable<Object> danaPlays = () -> {
            go.await();
            return engine.startGroup(dana, "dana");
        };
        Callable<Object> omerPlays = () -> {
            go.await();
            return engine.startGroup(omer, "omer");
        };
        Future<Object> first = users.submit(danaPlays);
        Future<Object> second = users.submit(omerPlays);
        go.countDown(); // both press Play now

        int started = 0;
        ReplayFailure refused = null;
        for (Future<Object> attempt : List.of(first, second)) {
            try {
                attempt.get();
                started++;
            } catch (java.util.concurrent.ExecutionException e) {
                refused = (ReplayFailure) e.getCause();
            }
        }
        users.shutdown();

        assertEquals(1, started, "6 + 6 > 10: exactly one group may start");
        assertEquals("ACTIVE_LIMIT", refused.code());
        assertEquals(Map.of("free", 4, "requested", 6, "max", MAX), refused.params());
        assertEquals(6, engine.activeReplays(), "waiting replays count as active");
    }

    @Test
    void manyUsersStartingSingleReplaysAtOnceGetExactlyTenSlots() throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService users = Executors.newFixedThreadPool(20);
        List<Future<?>> attempts = new ArrayList<>();
        for (int user = 0; user < 20; user++) {
            int port = freePort();
            attempts.add(users.submit(() -> {
                go.await();
                return engine.start(List.of(new ItemRequest("listen-single", new Target(null, port), 0)), false, "user");
            }));
        }
        go.countDown();

        int started = 0;
        int refused = 0;
        for (Future<?> attempt : attempts) {
            try {
                attempt.get();
                started++;
            } catch (java.util.concurrent.ExecutionException e) {
                assertEquals("ACTIVE_LIMIT", ((ReplayFailure) e.getCause()).code());
                refused++;
            }
        }
        users.shutdown();
        assertEquals(MAX, started);
        assertEquals(10, refused);
        assertEquals(MAX, engine.activeReplays());
    }

    @Test
    void aGroupLargerThanTheFreeSlotsIsRefusedWithTheNumbers() throws Exception {
        engine.startGroup(waitingGroup("a", 7), "dana");

        ReplayFailure failure = assertThrows(ReplayFailure.class, () -> engine.startGroup(waitingGroup("b", 5), "omer"));
        assertEquals("ACTIVE_LIMIT", failure.code());
        assertEquals(Map.of("free", 3, "requested", 5, "max", MAX), failure.params());
        assertEquals(7, engine.activeReplays());
    }

    @Test
    void slotsAreReleasedAfterEveryKindOfEnding() throws Exception {
        // normal end
        DatagramSocket service = udpReceiver(cleanup);
        engine.start(List.of(udp("udp-a", service)), false, "dana");
        waitUntil("completed", () -> listener.results().contains("COMPLETED"));
        waitUntil("slot back after completion", () -> engine.activeReplays() == 0);

        // failure while playing: TCP server mode, nobody listens on the target
        int closedPort = freePort();
        engine.start(List.of(new ItemRequest("tcpserver-a", new Target(LOCALHOST, closedPort), 0)), false, "dana");
        waitUntil("failed", () -> listener.results().contains("FAILED"));
        waitUntil("slot back after failure", () -> engine.activeReplays() == 0);

        // failure before the start: one member cannot be prepared, the group fails
        engine.startGroup(List.of(udp("udp-b", service), udp("bad-c", udpReceiver(cleanup))), "dana");
        waitUntil("slots back after group failure", () -> engine.activeReplays() == 0);

        // refused at start (busy target): no slot is kept
        engine.startGroup(waitingGroup("busy", 1), "dana");
        ItemRequest sameTarget = engine.views().get(0).items().stream()
                .map(item -> new ItemRequest("listen-other", new Target(null, item.port()), 0)).findFirst().orElseThrow();
        assertThrows(ReplayFailure.class, () -> engine.startGroup(List.of(sameTarget), "omer"));
        assertEquals(1, engine.activeReplays());

        // stop while waiting
        engine.stopAll("dana");
        waitUntil("slots back after stop", () -> engine.activeReplays() == 0);
        assertEquals(0, engine.views().size());
    }
}
