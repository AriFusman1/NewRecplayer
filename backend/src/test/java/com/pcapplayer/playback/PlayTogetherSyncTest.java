package com.pcapplayer.playback;

import com.pcapplayer.playback.model.HistoryEntry;
import com.pcapplayer.playback.model.ItemRequest;
import com.pcapplayer.playback.model.Target;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.pcapplayer.playback.ReplayTestKit.LOCALHOST;
import static com.pcapplayer.playback.ReplayTestKit.MS;
import static com.pcapplayer.playback.ReplayTestKit.freePort;
import static com.pcapplayer.playback.ReplayTestKit.udp;
import static com.pcapplayer.playback.ReplayTestKit.udpReceiver;
import static com.pcapplayer.playback.ReplayTestKit.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "Play together" starts every member at one shared instant, and is ready only once every target is. */
class PlayTogetherSyncTest {

    /** Generous for a loaded build machine; on an idle one the spread is well under 1 ms. */
    private static final long MAX_SPREAD_NANOS = 5 * MS;

    private final ReplayTestKit.RecordingListener listener = new ReplayTestKit.RecordingListener();
    private final ReplayEngine engine = ReplayTestKit.engine(listener, 10, 5000);
    private final List<AutoCloseable> cleanup = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        engine.stopAll("test");
        waitUntil("all replays ended", () -> engine.activeReplays() == 0);
        for (AutoCloseable closeable : cleanup) closeable.close();
    }

    /** The moment the first datagram arrives, taken by a thread of its own that is already blocked on receive. */
    private static CompletableFuture<Long> firstArrival(DatagramSocket socket) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                socket.receive(new DatagramPacket(new byte[8], 8));
                return System.nanoTime();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /**
     * Ten members on a laptop with fewer cores: the OS may deschedule one of them at the start instant
     * for a time slice, so a round may be a few ms off. A real fault (a member not using the shared
     * start, ~400 ms off because of the "slow-" records) fails every round, so the best of 3 counts.
     */
    @Test
    void tenMembersSendTheirFirstMessageAtTheSameInstant() throws Exception {
        List<Long> spreads = new ArrayList<>();

        for (int round = 0; round < 3; round++) {
            long spread = groupStartSpread();
            if (spread < MAX_SPREAD_NANOS) return;

            spreads.add(spread / 1000);
            waitUntil("round ended", () -> engine.activeReplays() == 0);
        }
        throw new AssertionError("first messages left " + spreads + " µs apart in 3 rounds");
    }

    /** Plays ten records together and returns how far apart their first messages arrived. */
    private long groupStartSpread() throws Exception {
        List<DatagramSocket> sockets = new ArrayList<>();
        List<ItemRequest> items = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            DatagramSocket socket = udpReceiver(cleanup);
            sockets.add(socket);
            items.add(udp((i % 3 == 0 ? "slow-" : "udp-") + i, socket)); // "slow-" records take ~400 ms to load
        }
        List<CompletableFuture<Long>> arrivals = sockets.stream().map(PlayTogetherSyncTest::firstArrival).toList();

        engine.startGroup(items, "dana");

        List<Long> times = new ArrayList<>();
        for (CompletableFuture<Long> arrival : arrivals) times.add(arrival.get(5, TimeUnit.SECONDS));
        return times.stream().mapToLong(Long::longValue).max().orElseThrow() - times.stream().mapToLong(Long::longValue).min().orElseThrow();
    }

    @Test
    void aTcpServerMemberThatCannotConnectStopsTheGroupBeforeAnythingPlays() throws Exception {
        DatagramSocket udpTarget = udpReceiver(cleanup);
        udpTarget.setSoTimeout(1500);
        ItemRequest refused = new ItemRequest("tcpserver-a", new Target(LOCALHOST, freePort()), 0); // nothing listens

        engine.startGroup(List.of(udp("udp-a", udpTarget), refused), "dana");

        AtomicBoolean udpReceived = new AtomicBoolean();
        try {
            udpTarget.receive(new DatagramPacket(new byte[8], 8));
            udpReceived.set(true);
        } catch (SocketTimeoutException e) {
            // expected: the group never started
        }
        waitUntil("both members ended", () -> listener.finished().size() == 2);
        assertTrue(!udpReceived.get(), "the UDP member played although the TCP server member could not connect");
        List<String> failures = listener.finished().stream().map(HistoryEntry::failureCode).sorted().toList();
        assertEquals(List.of("CONNECT_FAILED", "GROUP_FAILED"), failures);
    }
}
