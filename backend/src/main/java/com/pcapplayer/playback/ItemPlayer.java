package com.pcapplayer.playback;

import com.pcapplayer.pcapProcessing.script.ReplayScript;
import com.pcapplayer.pcapProcessing.script.ScriptStep;
import com.pcapplayer.playback.transport.Transport;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Plays one script through one transport. Every send time is computed from time zero
 * ({@code zero + offset}), never from the previous send, so there is no drift.
 * <p>
 * A seek moves time zero so the requested position is "now" and continues from the first step at
 * or after it. Steps jumped over are not sent; the connection stays open, so recorded
 * closes/reopens that are jumped over are ignored.
 * <p>
 * With a {@link TimeFixer}, each packet's bytes pass through it right before they are sent, so time
 * fields get the real send time. Without one, the recorded bytes are sent as they are.
 */
public final class ItemPlayer {

    interface Log {
        void log(String level, String code, Map<String, Object> params);
    }

    private ItemPlayer() {
    }

    static void play(ReplayScript script, Transport transport, Control control, ItemProgress progress,
                     Consumer<EItemState> state, Log log) {
        play(script, transport, control, progress, state, log, null, false);
    }

    /** @param timeFixer rewrites time fields at send time; null to send the recorded bytes */
    static void play(ReplayScript script, Transport transport, Control control, ItemProgress progress,
                     Consumer<EItemState> state, Log log, TimeFixer timeFixer) {
        play(script, transport, control, progress, state, log, timeFixer, false);
    }

    /** @param alreadyOpen the transport is connected already (a TCP client that connected while its group waited) */
    static void play(ReplayScript script, Transport transport, Control control, ItemProgress progress,
                     Consumer<EItemState> state, Log log, boolean alreadyOpen) {
        play(script, transport, control, progress, state, log, null, alreadyOpen);
    }

    /**
     * @param timeFixer   rewrites time fields at send time; null to send the recorded bytes
     * @param alreadyOpen the transport is connected already (a TCP client that connected while its group waited)
     */
    static void play(ReplayScript script, Transport transport, Control control, ItemProgress progress,
                     Consumer<EItemState> state, Log log, TimeFixer timeFixer, boolean alreadyOpen) {
        if (!alreadyOpen) {
            state.accept(transport.waitsForPeer() ? EItemState.WAITING_FOR_CONNECTION : EItemState.CONNECTING);
            transport.open(control);
        }
        control.takeSeek(); // a jump requested before playback started has nothing to apply to
        long zeroNanos = System.nanoTime();
        progress.start(zeroNanos);
        state.accept(EItemState.PLAYING);
        // else logged when it connected; a keep-trying transport logs its own connects
        if (!alreadyOpen && transport.isConnected()) log.log("info", "LOG_CONNECTED", Map.of());

        List<ScriptStep> steps = script.steps();
        int i = 0;

        while (true) {
            if (i >= steps.size()) {
                // Run to the end of the selected range so the timeline (and a following playlist sleep) is faithful.
                if (PreciseClock.sleepUntilOrSeek(zeroNanos + script.durationNanos(), control)) return;
            } else {
                ScriptStep step = steps.get(i);

                if (PreciseClock.sleepUntilOrSeek(zeroNanos + step.offsetNanos(), control)) {
                    zeroNanos = run(i, step, zeroNanos, transport, control, progress, state, log, timeFixer);
                    i++;
                    control.checkAlive();
                    continue;
                }
            }
            // a seek is pending
            long target = Math.min(control.takeSeek(), script.durationNanos());
            if (target < 0) continue;
            long messages = 0, bytes = 0;
            int next = 0;

            while (next < steps.size() && steps.get(next).offsetNanos() < target) {
                if (steps.get(next) instanceof ScriptStep.Send send) {
                    messages++;
                    bytes += send.data().length;
                }
                next++;
            }
            i = next;
            if (timeFixer != null) timeFixer.seekTo(next);
            zeroNanos = System.nanoTime() - target;
            progress.seek(zeroNanos, messages, bytes);
            state.accept(EItemState.PLAYING); // republish so viewers see the jump at once
        }
    }

    /** Executes one step that is due. @return time zero, which a reconnect wait can move */
    private static long run(int index, ScriptStep step, long zeroNanos, Transport transport, Control control,
                            ItemProgress progress, Consumer<EItemState> state, Log log, TimeFixer timeFixer) {
        if (step instanceof ScriptStep.Send send) {
            byte[] data = timeFixer == null ? send.data() : timeFixer.bytesToSend(index, send.data());
            // a keep-trying TCP transport without a connection does not deliver it
            if (transport.send(data, control)) progress.sent(send.data().length);
            else progress.missed();
        } else if (step instanceof ScriptStep.Gap gap) {
            log.log("warning", "LOG_GAP", Map.of("bytes", gap.bytes(), "offsetMs", TimeUnit.NANOSECONDS.toMillis(gap.offsetNanos())));
        } else if (step instanceof ScriptStep.Reconnect reconnect) {
            transport.closeConnection();
            log.log("info", "LOG_RECORDED_CLOSE", Map.of());

            if (transport.waitsForPeer()) {
                // Wait for the service to reconnect; the rest of the schedule shifts by the wait.
                progress.freeze(reconnect.connectOffsetNanos());
                state.accept(EItemState.WAITING_FOR_CONNECTION);
                transport.reopen(control);
                zeroNanos = System.nanoTime() - reconnect.connectOffsetNanos();
                progress.start(zeroNanos);
                state.accept(EItemState.PLAYING);
            } else {
                // not seekable: the connection is closed until this reopen
                PreciseClock.sleepUntil(zeroNanos + reconnect.connectOffsetNanos(), control);
                transport.reopen(control);
            }
            if (transport.isConnected()) log.log("info", "LOG_CONNECTED", Map.of());
        }
        return zeroNanos;
    }
}
