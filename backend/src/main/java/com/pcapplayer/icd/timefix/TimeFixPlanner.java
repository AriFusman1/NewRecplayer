package com.pcapplayer.icd.timefix;

import com.pcapplayer.icd.analysis.MessageAnalyzer;
import com.pcapplayer.icd.decode.ByteSpan;
import com.pcapplayer.icd.decode.DecodedMessage;
import com.pcapplayer.icd.decode.FieldSlot;
import com.pcapplayer.icd.decode.FieldValue;
import com.pcapplayer.icd.profile.MessageTimeFix;
import com.pcapplayer.icd.profile.TimeFieldRef;
import com.pcapplayer.icd.profile.TimeFixProfile;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Builds the {@link PatchPlan} of a record from its decoded messages and the category's profile.
 * <p>
 * For each message whose opcode the profile covers: the base field's original value becomes an
 * instant (LTime/GPS anchored to the capture date), every field to fix also becomes an instant, and
 * the plan keeps each field's offset from the base. At send time only {@code now + offset} has to be
 * encoded and written. A field selected as {@code targets[*].x} is planned in every element.
 * <p>
 * Problems (no base value, a value that is not a time) leave the message or field unchanged; they are
 * counted in {@link TimeFixStats}.
 */
public final class TimeFixPlanner implements MessageAnalyzer {

    private static final Pattern INDEX = Pattern.compile("\\[\\d+]");

    private final Map<Long, MessageTimeFix> byOpcode = new HashMap<>();
    private final PostPatchHook hook;
    private final TimeFixStats stats;
    private final Map<Integer, List<MessagePatch>> byFirstStep = new HashMap<>();
    private final BitSet patchedSteps = new BitSet();
    private int messageCount;
    private int maxSpan;

    public TimeFixPlanner(TimeFixProfile profile, PostPatchHook hook, TimeFixStats stats) {
        for (MessageTimeFix message : profile.messages()) byOpcode.put(message.opcode(), message);
        this.hook = hook;
        this.stats = stats;
    }

    @Override
    public void onMessage(DecodedMessage message) {
        MessageTimeFix fix = byOpcode.get(message.opcode());
        if (fix == null || fix.base() == null) return;

        FieldValue baseValue = message.fields().get(fix.base().path());
        FieldSlot baseSlot = message.slots().get(fix.base().path());

        if (baseValue == null || baseSlot == null) {
            stats.skippedMessage(message.name(), "base field " + fix.base().path() + " is not in the message");
            return;
        }
        long baseInstant;

        try {
            baseInstant = TimeCodec.toInstant(baseValue, fix.base().kind(), message.captureTsNanos());
        } catch (InvalidTimeException e) {
            stats.skippedMessage(message.name(), "base field " + fix.base().path() + ": " + e.getMessage());
            return;
        }
        List<FieldPatch> fields = new ArrayList<>();
        fields.add(new FieldPatch(fix.base().path(), fix.base().kind(), baseSlot, message.location(fix.base().path()), 0));
        for (TimeFieldRef ref : fix.fields()) addField(message, ref, baseInstant, fields);
        add(new MessagePatch(message.opcode(), message.name(), message.firstStep(), message.lastStep(), message.pieces(),
                List.copyOf(fields), hook));
    }

    /** Adds the field at {@code ref.path()}, or every array element it stands for. */
    private void addField(DecodedMessage message, TimeFieldRef ref, long baseInstant, List<FieldPatch> fields) {
        for (Map.Entry<String, FieldSlot> slot : message.slots().entrySet()) {
            String path = slot.getKey();
            if (!path.equals(ref.path()) && !INDEX.matcher(path).replaceAll("[*]").equals(ref.path())) continue;

            try {
                long instant = TimeCodec.toInstant(message.fields().get(path), ref.kind(), message.captureTsNanos());
                fields.add(new FieldPatch(path, ref.kind(), slot.getValue(), message.location(path), instant - baseInstant));
            } catch (InvalidTimeException e) {
                stats.skippedField(message.name(), path, e.getMessage());
            }
        }
    }

    private void add(MessagePatch patch) {
        byFirstStep.computeIfAbsent(patch.firstStep(), step -> new ArrayList<>()).add(patch);

        for (FieldPatch field : patch.fields()) {
            for (ByteSpan span : field.spans()) patchedSteps.set(span.step());
        }
        messageCount++;
        maxSpan = Math.max(maxSpan, patch.lastStep() - patch.firstStep());
        stats.planned();
    }

    /** The plan built from the messages seen so far (call after decoding). */
    public PatchPlan plan() {
        return new PatchPlan(Map.copyOf(byFirstStep), (BitSet) patchedSteps.clone(), messageCount, maxSpan);
    }
}
