package com.pcapplayer.playback;

import com.pcapplayer.pcapProcessing.script.EConnectionMode;
import com.pcapplayer.pcapProcessing.script.Histogram;
import com.pcapplayer.pcapProcessing.script.ReplayScript;
import com.pcapplayer.pcapProcessing.script.ScriptStep;
import com.pcapplayer.playback.model.HistoryEntry;
import com.pcapplayer.playback.model.ItemRequest;
import com.pcapplayer.playback.model.ReplayListener;
import com.pcapplayer.playback.model.ScriptSource;
import com.pcapplayer.playback.model.Target;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shared fakes and helpers for the engine tests: no captures or catalog, real sockets on localhost. */
final class ReplayTestKit {

    static final long MS = 1_000_000;
    static final String LOCALHOST = "127.0.0.1";

    private ReplayTestKit() {
    }

    /**
     * The record id decides the record: "listen-*" is a TCP client (we listen), "tcpserver-*" a TCP
     * server (we connect), anything else UDP. "slow-*" takes 400 ms to prepare, "bad-*" cannot be
     * prepared. Every script sends at 0, 100 and 200 ms and lasts 250 ms.
     */
    static final class FakeSource implements ScriptSource {
        @Override
        public RecordInfo describe(String recordId) {
            EConnectionMode mode = recordId.startsWith("listen") ? EConnectionMode.TCP_CLIENT
                    : recordId.startsWith("tcpserver") ? EConnectionMode.TCP_SERVER : EConnectionMode.UDP_SERVER;
            return new RecordInfo(recordId, recordId, "category", mode);
        }

        @Override
        public Prepared prepare(String recordId) {
            if (recordId.startsWith("slow")) sleep(400);
            if (recordId.startsWith("bad")) throw new ReplayFailure("PREPARE_FAILED", Map.of(), "cannot read");
            List<ScriptStep> steps = List.of(new ScriptStep.Send(0, new byte[]{1}),
                    new ScriptStep.Send(100 * MS, new byte[]{2}), new ScriptStep.Send(200 * MS, new byte[]{3}));
            ReplayScript script = new ReplayScript(describe(recordId).mode(), steps, 3, 3, 250 * MS);
            return new Prepared(describe(recordId), 0, script, Histogram.of(script, 10), null); // no time fix
        }
    }

    /** Records finished items and popups. */
    static final class RecordingListener implements ReplayListener {
        private final List<HistoryEntry> finished = new ArrayList<>();
        private final List<String> notices = new ArrayList<>();
        private final List<Map<String, Object>> noticeParams = new ArrayList<>();

        @Override
        public void sessionsChanged() {
        }

        @Override
        public synchronized void notice(String level, String code, Map<String, Object> params, String sessionId, String startedBy) {
            notices.add(code);
            noticeParams.add(params);
        }

        @Override
        public synchronized void itemFinished(HistoryEntry entry) {
            finished.add(entry);
        }

        synchronized List<HistoryEntry> finished() {
            return new ArrayList<>(finished);
        }

        synchronized List<String> results() {
            return finished.stream().map(HistoryEntry::result).toList();
        }

        synchronized List<String> notices() {
            return new ArrayList<>(notices);
        }

        synchronized Map<String, Object> lastNoticeParams() {
            return noticeParams.get(noticeParams.size() - 1);
        }
    }

    static ReplayEngine engine(ReplayListener listener, int maxActive, long groupConnectTimeoutMs) {
        return new ReplayEngine(new FakeSource(), listener, new ReplayEngine.Settings(1000, maxActive, groupConnectTimeoutMs, false, 1000));
    }

    static DatagramSocket udpReceiver(List<AutoCloseable> cleanup) throws Exception {
        DatagramSocket socket = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        socket.setSoTimeout(3000);
        cleanup.add(socket);
        return socket;
    }

    static ItemRequest udp(String recordId, DatagramSocket to) {
        return new ItemRequest(recordId, new Target(LOCALHOST, to.getLocalPort()), 0);
    }

    static ItemRequest listen(String recordId, int port) {
        return new ItemRequest(recordId, new Target(null, port), 0);
    }

    static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** True if nothing is listening on (or holding) the port any more. */
    static boolean portIsFree(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            return socket.getLocalPort() == port;
        } catch (Exception e) {
            return false;
        }
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void waitUntil(String what, BooleanSupplier condition) {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < end, "timed out waiting for: " + what);
            sleep(10);
        }
    }
}
