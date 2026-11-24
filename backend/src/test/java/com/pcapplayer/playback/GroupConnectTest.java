package com.pcapplayer.playback;

import com.pcapplayer.playback.model.SessionView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

import static com.pcapplayer.playback.ReplayTestKit.LOCALHOST;
import static com.pcapplayer.playback.ReplayTestKit.MS;
import static com.pcapplayer.playback.ReplayTestKit.freePort;
import static com.pcapplayer.playback.ReplayTestKit.listen;
import static com.pcapplayer.playback.ReplayTestKit.portIsFree;
import static com.pcapplayer.playback.ReplayTestKit.sleep;
import static com.pcapplayer.playback.ReplayTestKit.udp;
import static com.pcapplayer.playback.ReplayTestKit.udpReceiver;
import static com.pcapplayer.playback.ReplayTestKit.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A group with TCP clients waits until all of them are connected, then everyone starts together. */
class GroupConnectTest {

    private static final long TIMEOUT_MS = 1500;

    private final ReplayTestKit.RecordingListener listener = new ReplayTestKit.RecordingListener();
    private final ReplayEngine engine = ReplayTestKit.engine(listener, 10, TIMEOUT_MS);
    private final List<AutoCloseable> cleanup = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        engine.stopAll("test");
        waitUntil("all replays ended", () -> engine.activeReplays() == 0);
        for (AutoCloseable closeable : cleanup) closeable.close();
    }

    /** Connects as the service would; the socket is closed after the test. */
    private Socket connect(int port) throws Exception {
        Socket socket = new Socket(LOCALHOST, port);
        socket.setSoTimeout(3000);
        cleanup.add(socket);
        return socket;
    }

    private SessionView.Group group() {
        return engine.views().get(0).group();
    }

    @Test
    void nothingPlaysUntilBothTcpClientsAreConnected() throws Exception {
        DatagramSocket udpService = udpReceiver(cleanup);
        udpService.setSoTimeout(300);
        int port1 = freePort();
        int port2 = freePort();
        engine.startGroup(List.of(udp("udp-a", udpService), listen("listen-1", port1), listen("listen-2", port2)), "dana");

        waitUntil("waiting for connections", () -> group().tcpClients() == 2 && !group().started());
        Socket client1 = connect(port1);
        waitUntil("one client counted", () -> group().connectedClients() == 1);

        // one of two connected: the UDP member must still be silent
        assertThrows(SocketTimeoutException.class, () -> udpService.receive(new DatagramPacket(new byte[8], 8)));
        assertTrue(engine.views().stream().noneMatch(view -> "PLAYING".equals(view.state())));

        Socket client2 = connect(port2);
        udpService.setSoTimeout(3000);
        udpService.receive(new DatagramPacket(new byte[8], 8));
        long udpFirst = System.nanoTime();
        assertEquals(1, client1.getInputStream().read());
        long client1First = System.nanoTime();
        assertEquals(1, client2.getInputStream().read());
        long client2First = System.nanoTime();

        assertTrue(group().started());
        long spread = Math.max(udpFirst, Math.max(client1First, client2First)) - Math.min(udpFirst, Math.min(client1First, client2First));
        assertTrue(spread < 100 * MS, "members started " + spread / MS + " ms apart");
        waitUntil("all completed", () -> listener.results().size() == 3);
        assertEquals(List.of("COMPLETED", "COMPLETED", "COMPLETED"), listener.results());
    }

    @Test
    void aClientThatDisconnectsBeforeTheStartIsCountedAsNotConnectedAgain() throws Exception {
        int port1 = freePort();
        int port2 = freePort();
        engine.startGroup(List.of(listen("listen-1", port1), listen("listen-2", port2)), "dana");

        Socket first = new Socket(LOCALHOST, port1);
        waitUntil("first client counted", () -> group().connectedClients() == 1);
        first.close();
        waitUntil("first client no longer counted", () -> group().connectedClients() == 0);

        Socket again = connect(port1);
        Socket second = connect(port2);
        assertEquals(1, again.getInputStream().read(), "the group started after the reconnect");
        assertEquals(1, second.getInputStream().read());
        waitUntil("both completed", () -> listener.results().size() == 2);
        assertEquals(List.of("COMPLETED", "COMPLETED"), listener.results());
    }

    @Test
    void theGroupFailsWhenNotAllClientsConnectInTime() throws Exception {
        DatagramSocket udpService = udpReceiver(cleanup);
        int port1 = freePort();
        int port2 = freePort();
        engine.startGroup(List.of(udp("udp-a", udpService), listen("listen-1", port1), listen("listen-2", port2)), "dana");
        connect(port1);

        waitUntil("all members ended", () -> listener.results().size() == 3);
        assertTrue(listener.finished().stream().allMatch(entry -> "GROUP_CONNECT_TIMEOUT".equals(entry.failureCode())));
        assertEquals(List.of("GROUP_CONNECT_TIMEOUT"), listener.notices(), "announced once for the whole group");
        assertEquals(List.of("listen-2"), listener.lastNoticeParams().get("missing"));
        waitUntil("slots released", () -> engine.activeReplays() == 0);
        assertTrue(portIsFree(port1) && portIsFree(port2), "listen ports released");
    }

    @Test
    void stopWhileWaitingCancelsTheWholeGroup() throws Exception {
        DatagramSocket udpService = udpReceiver(cleanup);
        udpService.setSoTimeout(300);
        int port = freePort();
        List<SessionView> views = engine.startGroup(List.of(udp("udp-a", udpService), listen("listen-1", port)), "dana");
        waitUntil("waiting", () -> !engine.views().isEmpty() && !group().started());

        engine.stop(views.get(0).id(), "dana"); // Stop on one member

        waitUntil("both ended", () -> listener.results().size() == 2);
        assertEquals(List.of("STOPPED", "STOPPED"), listener.results());
        assertTrue(listener.finished().stream().allMatch(entry -> "dana".equals(entry.stoppedBy())));
        waitUntil("slots released", () -> engine.activeReplays() == 0);
        assertTrue(portIsFree(port), "listen port released");
        assertThrows(SocketTimeoutException.class, () -> udpService.receive(new DatagramPacket(new byte[8], 8)));
    }

    @Test
    void afterTheStartADisconnectFailsOnlyThatReplay() throws Exception {
        int port = freePort();
        engine.startGroup(List.of(listen("listen-1", port)), "dana");
        Socket client = connect(port);
        InputStream in = client.getInputStream();
        assertEquals(1, in.read()); // playing
        client.close();
        sleep(50);
        waitUntil("ended", () -> listener.results().size() == 1);
        assertEquals("CLIENT_DISCONNECTED", listener.finished().get(0).failureCode());
    }
}
