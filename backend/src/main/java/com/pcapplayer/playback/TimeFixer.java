package com.pcapplayer.playback;

import com.pcapplayer.icd.timefix.MessagePatch;
import com.pcapplayer.icd.timefix.PatchPlan;
import com.pcapplayer.icd.timefix.StepCopies;
import com.pcapplayer.icd.timefix.TimeFixStats;
import com.pcapplayer.pcapProcessing.script.ScriptStep;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Rewrites time fields while one item plays. The plan was built before playback; here only the
 * last step happens: right before a packet is sent, every message that starts in it gets
 * {@code now} (read from the clock at that moment) written into its fields.
 * <p>
 * A message can span several packets: its later packets were already patched when its first packet
 * was sent, and are sent from the same copies. Packets no patch touches are sent as they are, with no
 * copying.
 * <p>
 * After a seek that lands inside a message (its first packet skipped), the message is patched when
 * its first remaining packet is sent, using that moment as {@code now}.
 * <p>
 * One instance per item; used only by the playing thread.
 */
final class TimeFixer {

    private final PatchPlan plan;
    private final StepCopies copies;
    private final Clock clock;
    private final TimeFixStats stats = new TimeFixStats();

    /** After a seek: messages whose first packet was skipped; patched at the next send. */
    private final List<MessagePatch> resumed = new ArrayList<>();

    TimeFixer(PatchPlan plan, List<ScriptStep> steps, Clock clock) {
        this.plan = plan;
        this.copies = new StepCopies(steps);
        this.clock = clock;
    }

    /** The bytes to send for {@code step}: the original, or a patched copy. */
    byte[] bytesToSend(int step, byte[] original) {
        List<MessagePatch> starting = plan.startingAt(step);
        if (starting.isEmpty() && resumed.isEmpty() && !plan.writesInto(step)) return original; // the common case

        if (!starting.isEmpty() || !resumed.isEmpty()) {
            long now = nowNanos();
            for (MessagePatch patch : starting) patch.apply(now, copies, stats);

            for (MessagePatch patch : resumed) {
                if (patch.lastStep() >= step) patch.apply(now, copies, stats);
            }
            resumed.clear();
        }
        byte[] copy = copies.take(step);
        return copy != null ? copy : original;
    }

    /** Playback continues from {@code nextStep}: forget copies made for the old position. */
    void seekTo(int nextStep) {
        copies.clear();
        resumed.clear();

        for (int step = Math.max(0, nextStep - plan.maxSpan()); step < nextStep; step++) {
            for (MessagePatch patch : plan.startingAt(step)) {
                if (patch.lastStep() >= nextStep) resumed.add(patch);
            }
        }
    }

    TimeFixStats stats() {
        return stats;
    }

    private long nowNanos() {
        Instant now = clock.instant();
        return now.getEpochSecond() * 1_000_000_000L + now.getNano();
    }
}
