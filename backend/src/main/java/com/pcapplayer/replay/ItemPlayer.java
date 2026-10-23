package com.pcapplayer.replay;

import com.pcapplayer.extract.ReplayScript;
import com.pcapplayer.extract.ScriptStep;
import com.pcapplayer.replay.transport.Transport;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Plays one script through one transport. Every send time is computed from time zero
 * ({@code zero + offset}), never from the previous send, so there is no drift.
 */
final class ItemPlayer {

    interface Log {
        void log(String level, String code, Map<String, Object> params);
    }

    private ItemPlayer() {
    }

    static void play(ReplayScript script, Transport transport, Control control, ItemProgress progress,
                     Consumer<ItemState> state, Log log) {
        state.accept(transport.waitsForPeer() ? ItemState.WAITING_FOR_CONNECTION : ItemState.CONNECTING);
        transport.open(control);
        long zero = System.nanoTime();
        progress.start(zero);
        state.accept(ItemState.PLAYING);
        log.log("info", "LOG_CONNECTED", Map.of());

        for (ScriptStep step : script.steps()) {
            if (step instanceof ScriptStep.Send send) {
                PreciseClock.sleepUntil(zero + send.offsetNanos(), control);
                transport.send(send.data(), control);
                progress.sent(send.data().length);
            } else if (step instanceof ScriptStep.Gap gap) {
                PreciseClock.sleepUntil(zero + gap.offsetNanos(), control);
                log.log("warning", "LOG_GAP", Map.of("bytes", gap.bytes(), "offsetMs", gap.offsetNanos() / 1_000_000));
            } else if (step instanceof ScriptStep.Reconnect rc) {
                PreciseClock.sleepUntil(zero + rc.offsetNanos(), control);
                transport.closeConnection();
                log.log("info", "LOG_RECORDED_CLOSE", Map.of());
                if (transport.waitsForPeer()) {
                    // Wait for the service to reconnect; the rest of the schedule shifts by the wait.
                    progress.freeze(rc.connectOffsetNanos());
                    state.accept(ItemState.WAITING_FOR_CONNECTION);
                    transport.reopen(control);
                    zero = System.nanoTime() - rc.connectOffsetNanos();
                    progress.start(zero);
                    state.accept(ItemState.PLAYING);
                } else {
                    PreciseClock.sleepUntil(zero + rc.connectOffsetNanos(), control);
                    transport.reopen(control);
                }
                log.log("info", "LOG_CONNECTED", Map.of());
            }
            control.checkAlive();
        }
        // Run to the end of the selected range so the timeline (and a following playlist sleep) is faithful.
        PreciseClock.sleepUntil(zero + script.durationNanos(), control);
    }
}
