package com.pcapplayer.playback;

import com.pcapplayer.pcapProcessing.script.EConnectionMode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Replays started together ("Play together"): one single-item session per record, and one start
 * signal for all of them.
 * <p>
 * A member is <em>ready</em> when its script is loaded and, if it is a TCP client (we listen and the
 * service connects to us), when its service is connected. When every member is ready the group
 * <em>starts</em>: all waiting members are released at the same moment. A TCP client that
 * disconnects before the start is not ready any more and waits for a new connection.
 * <p>
 * The start is one instant shared by every member ({@link #startAtNanos()}), a little after the
 * last member got ready, so every member's thread is awake and waiting for it: each one takes it as
 * its time zero, and how late the OS wakes each thread does not matter.
 * <p>
 * Until the start the group stands or falls together: if one member fails, is stopped, or the TCP
 * clients don't all connect within the timeout, every member ends. After the start each member is
 * on its own again.
 * <p>
 * All state is guarded by {@code this}; members are notified outside the lock.
 */
final class ReplayGroup {

    final String id = UUID.randomUUID().toString();
    final int size;
    private final List<ReplaySession> members;
    /** Record name of every TCP-client member, by session id. */
    private final Map<String, String> tcpClients = new LinkedHashMap<>();

    /** How long after the last member got ready the group starts: time for every member's thread to wake. */
    static final long START_LEAD_NANOS = 25_000_000;

    private final Set<String> ready = new HashSet<>();
    private boolean started;
    /** The shared start instant ({@link System#nanoTime()}); set when the group starts. */
    private long startAtNanos;
    /** The group ended before it started (a member stopped or failed, or the timeout); it will never start. */
    private boolean ended;
    /** Why it ended, when that was a failure (members then fail with it); null for a stop. */
    private ReplayFailure failure;

    ReplayGroup(List<ReplaySession> members) {
        this.members = List.copyOf(members);
        this.size = members.size();

        for (ReplaySession member : members) {
            SessionItem item = member.firstItem();
            if (item.info.mode() == EConnectionMode.TCP_CLIENT) tcpClients.put(member.id, item.info.name());
        }
    }

    boolean hasTcpClients() {
        return !tcpClients.isEmpty();
    }

    // ------------------------------------------------------------------ called by members

    /** The member is ready (loaded, and connected if it is a TCP client). Starts the group if it was the last one. */
    synchronized void markReady(ReplaySession member) {
        ready.add(member.id);

        if (!started && !ended && ready.size() == size) {
            started = true;
            startAtNanos = System.nanoTime() + START_LEAD_NANOS;
            notifyAll(); // the start signal
        }
    }

    /** The instant every member starts at, as {@link System#nanoTime()}; valid once the group has started. */
    synchronized long startAtNanos() {
        return startAtNanos;
    }

    /** A TCP client lost its connection before the start: it is not ready until it connects again. */
    synchronized void markNotReady(ReplaySession member) {
        if (!started) ready.remove(member.id);
    }

    /**
     * Waits up to {@code ms} for the start signal.
     *
     * @return true once the group has started
     * @throws ReplayFailure why the group ended, if it ended before starting
     */
    synchronized boolean waitForStart(long ms) {
        if (failure != null) throw failure;

        if (!started) {
            try {
                wait(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // the member's Control reports the stop or failure
            }
        }
        if (failure != null) throw failure;
        return started;
    }

    /**
     * A member ended. If that happened before the start, the whole group ends with it: when the
     * member was stopped the others are stopped by the same person, when it failed the others fail
     * with GROUP_FAILED naming it.
     */
    void memberEnded(ReplaySession member, String stoppedBy, ReplayFailure memberFailure) {
        List<ReplaySession> others;

        synchronized (this) {
            if (started || ended) return;
            ended = true;

            if (memberFailure != null) {
                String record = member.firstItem().info.name();
                failure = new ReplayFailure("GROUP_FAILED", Map.of("record", record, "reason", memberFailure.code()),
                        record + " failed before the group started (" + memberFailure.code() + ")");
                notifyAll();
            }
            // a stop needs no wake-up: each member notices its own Stop within one wait slice
            others = otherMembers(member);
        }
        for (ReplaySession other : others) {
            if (memberFailure == null) other.stop(stoppedBy);
            else other.failFromGroup(failure);
        }
    }

    /**
     * Called when the connect timeout expires. If the group is still waiting, every member fails
     * with GROUP_CONNECT_TIMEOUT listing the TCP clients that are not connected.
     *
     * @return the failure, or null if the group had already started or ended
     */
    ReplayFailure timeOut(long timeoutMs) {
        List<ReplaySession> all;
        ReplayFailure timedOut;

        synchronized (this) {
            if (started || ended) return null;
            ended = true;
            List<String> missing = new ArrayList<>();
            tcpClients.forEach((sessionId, name) -> {
                if (!ready.contains(sessionId)) missing.add(name);
            });
            timedOut = new ReplayFailure("GROUP_CONNECT_TIMEOUT",
                    Map.of("missing", missing, "seconds", timeoutMs / 1000),
                    "Not connected after " + timeoutMs / 1000 + " s: " + String.join(", ", missing));
            failure = timedOut;
            notifyAll();
            all = new ArrayList<>(members);
        }
        all.forEach(member -> member.failFromGroup(timedOut));
        return timedOut;
    }

    private List<ReplaySession> otherMembers(ReplaySession member) {
        List<ReplaySession> others = new ArrayList<>(members);
        others.remove(member);
        return others;
    }

    // ------------------------------------------------------------------ for the UI

    synchronized boolean started() {
        return started;
    }

    /** How many TCP-client members are connected right now (ready = connected for them). */
    synchronized int connectedClients() {
        return (int) tcpClients.keySet().stream().filter(ready::contains).count();
    }

    int tcpClientCount() {
        return tcpClients.size();
    }
}
