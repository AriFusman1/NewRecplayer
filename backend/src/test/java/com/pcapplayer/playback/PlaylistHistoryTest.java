package com.pcapplayer.playback;

import com.pcapplayer.common.Json;
import com.pcapplayer.playback.model.HistoryEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramSocket;
import java.util.ArrayList;
import java.util.List;

import static com.pcapplayer.playback.ReplayTestKit.udp;
import static com.pcapplayer.playback.ReplayTestKit.udpReceiver;
import static com.pcapplayer.playback.ReplayTestKit.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** History rows remember which saved playlist a run came from, so the History screen can filter on it. */
class PlaylistHistoryTest {

    private final ReplayTestKit.RecordingListener listener = new ReplayTestKit.RecordingListener();
    private final ReplayEngine engine = ReplayTestKit.engine(listener, 10, 5000);
    private final List<AutoCloseable> cleanup = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        engine.stopAll("test");
        waitUntil("all replays ended", () -> engine.activeReplays() == 0);
        for (AutoCloseable closeable : cleanup) closeable.close();
    }

    @Test
    void aSavedPlaylistRunRecordsItsName() throws Exception {
        DatagramSocket service = udpReceiver(cleanup);
        engine.start(List.of(udp("udp-a", service), udp("udp-b", service)), true, "Morning run", "dana");

        waitUntil("both items finished", () -> listener.finished().size() == 2);
        for (HistoryEntry entry : listener.finished()) {
            assertEquals("Morning run", entry.playlist().name());
            assertEquals(2, entry.playlist().total());
        }
    }

    @Test
    void aDraftPlaylistHasNoNameAndASingleReplayNoPlaylist() throws Exception {
        DatagramSocket service = udpReceiver(cleanup);
        engine.start(List.of(udp("udp-a", service)), true, null, "dana");
        waitUntil("draft finished", () -> listener.finished().size() == 1);
        assertNull(listener.finished().get(0).playlist().name());

        engine.start(List.of(udp("udp-b", service)), false, "ignored for a single replay", "dana");
        waitUntil("single finished", () -> listener.finished().size() == 2);
        assertNull(listener.finished().get(1).playlist());
    }

    @Test
    void olderHistoryEntriesWithoutANameStillLoad() {
        String older = """
                {"id":"h1","recordId":"r1","recordName":"Radar","categoryName":"UDP","mode":"UDP_SERVER",
                 "target":"10.0.0.5:5000","startedBy":"dana","startedAtMs":1,"endedAtMs":2,"messagesSent":3,
                 "totalMessages":3,"bytesSent":30,"result":"COMPLETED",
                 "playlist":{"sessionId":"s1","index":1,"total":2}}""";
        HistoryEntry entry = Json.read(older.getBytes(), HistoryEntry.class);
        assertEquals("s1", entry.playlist().sessionId());
        assertNull(entry.playlist().name());
    }
}
