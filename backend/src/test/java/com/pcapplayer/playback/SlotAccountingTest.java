package com.pcapplayer.playback;

import com.pcapplayer.playback.model.HistoryEntry;
import com.pcapplayer.playback.model.ItemRequest;
import com.pcapplayer.playback.model.SessionView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.logging.Logger;

import static com.pcapplayer.playback.ReplayTestKit.freePort;
import static com.pcapplayer.playback.ReplayTestKit.listen;
import static com.pcapplayer.playback.ReplayTestKit.portIsFree;
import static com.pcapplayer.playback.ReplayTestKit.udp;
import static com.pcapplayer.playback.ReplayTestKit.udpReceiver;
import static com.pcapplayer.playback.ReplayTestKit.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every replay slot is given back exactly once, however the replay ends: the counter returns to 0,
 * and the targets and listening ports are free again.
 */
class SlotAccountingTest {

    private static final int MAX = 10;

    private final ReplayTestKit.RecordingListener listener = new ReplayTestKit.RecordingListener();
    private final List<AutoCloseable> cleanup = new ArrayList<>();
    private ReplayEngine engine = engineWith(Thread::new, 60_000);

    @AfterEach
    void tearDown() throws Exception {
        engine.stopAll("test");
        waitUntil("all replays ended", () -> engine.activeReplays() == 0);
        for (AutoCloseable closeable : cleanup) closeable.close();
    }

    private ReplayEngine engineWith(ThreadFactory threads, long groupConnectTimeoutMs) {
        return new ReplayEngine(new ReplayTestKit.FakeSource(), listener,
                new ReplayEngine.Settings(1000, MAX, groupConnectTimeoutMs, false, 1000), threads);
    }

    private void assertAllGiven(String why) {
        waitUntil("slots back after " + why, () -> engine.activeReplays() == 0);
        waitUntil("no sessions after " + why, () -> engine.views().isEmpty());
    }

    @Test
    void aReplayThatCompletesGivesItsSlotBack() throws Exception {
        engine.start(List.of(udp("udp-a", udpReceiver(cleanup))), false, "dana");
        assertEquals(1, engine.activeReplays());

        waitUntil("completed", () -> listener.results().contains("COMPLETED"));
        assertAllGiven("completion");
    }

    @Test
    void aReplayStoppedByTheUserGivesItsSlotAndPortBack() throws Exception {
        int port = freePort();
        SessionView session = engine.start(List.of(listen("listen-a", port)), false, "dana"); // waits for a connection

        engine.stop(session.id(), "dana");

        assertAllGiven("stop");
        assertTrue(portIsFree(port));
    }

    @Test
    void aReplayRefusedWhileReservingItsTargetHoldsNoSlot() throws Exception {
        try (ServerSocket taken = new ServerSocket(0)) { // somebody else listens on the port
            ReplayFailure failure = assertThrows(ReplayFailure.class,
                    () -> engine.start(List.of(listen("listen-a", taken.getLocalPort())), false, "dana"));

            assertEquals("LISTEN_FAILED", failure.code());
            assertEquals(0, engine.activeReplays());
        }
    }

    @Test
    void aReplayWhoseThreadCannotStartGivesEverythingBack() throws Exception {
        engine = engineWith(new FailingThreadFactory(1), 60_000);
        int port = freePort();

        ReplayFailure failure = assertThrows(ReplayFailure.class,
                () -> engine.start(List.of(listen("listen-a", port)), false, "dana"));

        assertEquals("START_FAILED", failure.code());
        assertEquals(503, failure.status());
        assertEquals(0, engine.activeReplays());
        assertTrue(engine.views().isEmpty());
        assertTrue(portIsFree(port), "the port bound before the start is closed again");
    }

    @Test
    void aGroupWhoseThirdThreadCannotStartEndsTheMembersAlreadyRunning() throws Exception {
        engine = engineWith(new FailingThreadFactory(3), 60_000);
        List<Integer> ports = List.of(freePort(), freePort(), freePort(), freePort());
        List<ItemRequest> items = ports.stream().map(port -> listen("listen-" + port, port)).toList();

        ReplayFailure failure = assertThrows(ReplayFailure.class, () -> engine.startGroup(items, "dana"));

        assertEquals("START_FAILED", failure.code());
        assertEquals("listen-" + ports.get(2), failure.params().get("record"));
        assertAllGiven("a member could not start");
        for (int port : ports) waitUntil("port " + port + " free", () -> portIsFree(port));
        waitUntil("the two running members ended", () -> listener.finished().size() == 2);
        assertEquals(List.of("GROUP_FAILED", "GROUP_FAILED"), listener.finished().stream().map(HistoryEntry::failureCode).toList());
    }

    @Test
    void aGroupWhoseTcpClientsDoNotConnectInTimeGivesItsSlotsBack() throws Exception {
        engine = engineWith(Thread::new, 300);

        engine.startGroup(List.of(listen("listen-a", freePort()), udp("udp-b", udpReceiver(cleanup))), "dana");
        assertEquals(2, engine.activeReplays());

        waitUntil("timed out", () -> listener.notices().contains("GROUP_CONNECT_TIMEOUT"));
        assertAllGiven("the connect timeout");
    }

    @Test
    void aGroupWithAMemberThatFailsGivesEverySlotBack() throws Exception {
        engine.startGroup(List.of(udp("udp-a", udpReceiver(cleanup)), udp("bad-b", udpReceiver(cleanup)),
                listen("listen-c", freePort())), "dana");

        waitUntil("all three ended", () -> listener.finished().size() == 3);
        assertAllGiven("a member failed");
    }

    @Test
    void aGroupMemberStoppedBeforeItsThreadRunsEndsTheWholeGroup() throws Exception {
        GatedThreadFactory gated = new GatedThreadFactory();
        engine = engineWith(gated, 60_000);
        int stoppedPort = freePort();
        int otherPort = freePort();
        List<SessionView> members = engine.startGroup(List.of(listen("listen-a", stoppedPort), listen("listen-b", otherPort)), "dana");

        engine.stop(members.get(0).id(), "dana"); // before its thread has done anything
        gated.open();

        assertAllGiven("a stop before the thread ran");
        assertTrue(portIsFree(stoppedPort), "the stopped member's pre-bound port is closed");
        assertTrue(portIsFree(otherPort));
    }

    @Test
    void shutdownStopsEveryReplayWaitsForThemAndRefusesNewOnes() throws Exception {
        DatagramSocket service = udpReceiver(cleanup);
        engine.start(List.of(listen("listen-a", freePort())), false, "dana");
        engine.startGroup(List.of(listen("listen-b", freePort()), listen("listen-c", freePort())), "omer");
        assertEquals(3, engine.activeReplays());

        assertTrue(engine.shutdown("server shutdown", 3000), "every replay ended within the grace time");

        assertEquals(0, engine.activeReplays());
        ReplayFailure refused = assertThrows(ReplayFailure.class, () -> engine.start(List.of(udp("udp-d", service)), false, "dana"));
        assertEquals("SHUTTING_DOWN", refused.code());
    }

    @Test
    void twentyUsersPressingPlayAtOnceGetExactlyTenSlotsAndAllComeBack() throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService users = Executors.newFixedThreadPool(20);
        List<Future<SessionView>> attempts = new ArrayList<>();

        for (int user = 0; user < 20; user++) {
            // mixed: UDP replays end by themselves, TCP clients wait until stopped
            ItemRequest item = user % 2 == 0 ? udp("udp-" + user, udpReceiver(cleanup)) : listen("listen-" + user, freePort());
            attempts.add(users.submit(() -> {
                go.await();
                return engine.start(List.of(item), false, "user");
            }));
        }
        go.countDown();
        int started = 0;
        int refused = 0;

        for (Future<SessionView> attempt : attempts) {
            try {
                attempt.get();
                started++;
            } catch (ExecutionException e) {
                assertEquals("ACTIVE_LIMIT", ((ReplayFailure) e.getCause()).code());
                refused++;
            }
        }
        users.shutdown();

        assertEquals(MAX, started);
        assertEquals(20 - MAX, refused);
        engine.stopAll("test");
        assertAllGiven("all were stopped");
    }

    @Test
    void releasingMoreThanWasTakenIsLoggedAndTheCounterStaysAtZero() {
        ActiveReplays slots = new ActiveReplays(MAX);
        CapturingLogHandler log = new CapturingLogHandler();
        Logger logger = Logger.getLogger(ActiveReplays.class.getName());
        logger.addHandler(log);

        try {
            slots.take(1);
            slots.release(1);
            assertTrue(log.messages().isEmpty(), "a normal release logs nothing");

            slots.release(1); // the same slot again

            assertEquals(0, slots.active());
            assertEquals(1, log.messages().size());
            assertTrue(log.messages().get(0).startsWith("WARNING"), log.messages().get(0));
        } finally {
            logger.removeHandler(log);
        }
    }

    @Test
    void aTargetBusyRefusalInsideAGroupGivesBackEverySlotItTook() throws Exception {
        int busyPort = freePort();
        engine.start(List.of(listen("listen-a", busyPort)), false, "dana"); // holds the port, waiting

        ReplayFailure failure = assertThrows(ReplayFailure.class, () -> engine.startGroup(
                List.of(udp("udp-b", udpReceiver(cleanup)), listen("listen-c", busyPort)), "omer"));

        assertEquals("TARGET_BUSY", failure.code());
        assertEquals(1, engine.activeReplays(), "only the first replay holds a slot");
        engine.stopAll("dana");
        assertAllGiven("the first replay was stopped");
    }
}
