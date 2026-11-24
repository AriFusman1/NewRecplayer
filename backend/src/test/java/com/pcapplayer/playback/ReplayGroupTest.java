package com.pcapplayer.playback;

import com.pcapplayer.playback.model.ItemRequest;
import com.pcapplayer.playback.model.SessionView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.pcapplayer.playback.ReplayTestKit.MS;
import static com.pcapplayer.playback.ReplayTestKit.freePort;
import static com.pcapplayer.playback.ReplayTestKit.listen;
import static com.pcapplayer.playback.ReplayTestKit.portIsFree;
import static com.pcapplayer.playback.ReplayTestKit.udp;
import static com.pcapplayer.playback.ReplayTestKit.udpReceiver;
import static com.pcapplayer.playback.ReplayTestKit.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "Play together" without TCP clients: reservation is all or nothing, and the members start together. */
class ReplayGroupTest {

    private final ReplayTestKit.RecordingListener listener = new ReplayTestKit.RecordingListener();
    private final ReplayEngine engine = ReplayTestKit.engine(listener, 10, 5000);
    private final List<AutoCloseable> cleanup = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        engine.stopAll("test");
        waitUntil("all replays ended", () -> engine.activeReplays() == 0);
        for (AutoCloseable closeable : cleanup) closeable.close();
    }

    private DatagramSocket receiver() throws Exception {
        return udpReceiver(cleanup);
    }

    @Test
    void aGroupWithoutTcpClientsStartsAtOnceAndTogether() throws Exception {
        List<DatagramSocket> sockets = List.of(receiver(), receiver(), receiver());
        List<SessionView> views = engine.startGroup(List.of(
                udp("udp-a", sockets.get(0)), udp("slow-b", sockets.get(1)), udp("udp-c", sockets.get(2))), "dana");

        assertEquals(3, views.size());
        Set<String> groupIds = views.stream().map(view -> view.group().id()).collect(Collectors.toSet());
        assertEquals(1, groupIds.size(), "all sessions share one group");
        assertTrue(views.stream().allMatch(view -> view.group().size() == 3 && view.group().tcpClients() == 0));

        // first datagram of each member: without the common start, a and c would be ~400 ms ahead of b
        List<Long> firstArrival = new ArrayList<>();
        for (DatagramSocket socket : sockets) {
            socket.receive(new DatagramPacket(new byte[8], 8));
            firstArrival.add(System.nanoTime());
        }
        long spread = firstArrival.stream().mapToLong(Long::longValue).max().orElseThrow()
                - firstArrival.stream().mapToLong(Long::longValue).min().orElseThrow();
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
        assertEquals(1, engine.activeReplays(), "the refused group's slots are given back");

        // the first member's target was released again
        assertEquals(2, engine.startGroup(List.of(udp("udp-a", free), udp("udp-c", receiver())), "dana").size());
    }

    @Test
    void listenPortIsReleasedWhenTheGroupIsRefused() throws Exception {
        int listenPort = freePort();
        DatagramSocket busy = receiver();
        engine.start(List.of(udp("udp-running", busy)), false, "other");

        assertThrows(ReplayFailure.class, () -> engine.startGroup(List.of(
                listen("listen-a", listenPort), udp("udp-b", busy)), "dana"));
        assertTrue(portIsFree(listenPort));
    }

    @Test
    void theSameTargetTwiceIsRefused() throws Exception {
        DatagramSocket socket = receiver();
        ReplayFailure failure = assertThrows(ReplayFailure.class,
                () -> engine.startGroup(List.of(udp("udp-a", socket), udp("udp-b", socket)), "dana"));
        assertEquals("DUPLICATE_TARGET", failure.code());
        assertEquals("udp-b", failure.params().get("record"));
        assertTrue(engine.views().isEmpty());
        assertEquals(0, engine.activeReplays());
    }

    @Test
    void playTogetherNeedsAtLeastTwoRecordings() throws Exception {
        DatagramSocket socket = receiver();
        for (List<ItemRequest> tooFew : List.of(List.<ItemRequest>of(), List.of(udp("udp-a", socket)))) {
            ReplayFailure failure = assertThrows(ReplayFailure.class, () -> engine.startGroup(tooFew, "dana"));
            assertEquals("GROUP_TOO_SMALL", failure.code());
            assertEquals(400, failure.status(), "a request that can never be valid is a 400");
            assertEquals(tooFew.size(), failure.params().get("requested"));
        }
        assertTrue(engine.views().isEmpty());
        assertEquals(0, engine.activeReplays(), "a refused request takes no slot");

        // the normal Play still runs a single recording
        engine.start(List.of(udp("udp-a", socket)), false, "dana");
        assertEquals(1, engine.views().size());
    }

    @Test
    void aMemberThatFailsBeforeTheStartFailsTheWholeGroup() throws Exception {
        DatagramSocket socket = receiver();
        socket.setSoTimeout(800);
        engine.startGroup(List.of(udp("udp-a", socket), udp("bad-b", receiver())), "dana");

        waitUntil("both members ended", () -> listener.results().size() == 2);
        assertEquals(List.of("FAILED", "FAILED"), listener.results());
        assertTrue(listener.finished().stream().anyMatch(entry -> "GROUP_FAILED".equals(entry.failureCode())));
        assertThrows(SocketTimeoutException.class, () -> socket.receive(new DatagramPacket(new byte[8], 8)),
                "the healthy member must not have played");
        assertFalse(listener.notices().contains("GROUP_FAILED"), "only the member that failed is announced");
        waitUntil("slots released", () -> engine.activeReplays() == 0);
    }
}
