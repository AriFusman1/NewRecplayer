package com.pcapplayer.icd.profile;

import com.pcapplayer.icd.model.EFieldType;
import com.pcapplayer.icd.timefix.ETimeKind;
import com.pcapplayer.icd.timefix.TimeCodec;
import com.pcapplayer.icd.tree.IcdTree;
import com.pcapplayer.icd.tree.IcdTreeMessage;
import com.pcapplayer.icd.tree.IcdTreeNode;
import com.pcapplayer.pcapProcessing.diagnostics.Warning;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks a time-fix profile against its ICD.
 * <p>
 * Errors: an unknown message or path, a field that cannot hold a time (not a number), fields without a
 * base, a base inside an array (which element would it be?), a field chosen twice, a missing kind.
 * <p>
 * Warnings: a type too small for the values the kind needs today (e.g. epoch milliseconds in an INT32),
 * and FLOAT32 where its 24-bit precision loses much more than a millisecond.
 */
public final class TimeFixProfileValidator {

    /** Largest exact integer of a FLOAT32. */
    private static final long FLOAT32_EXACT = 1L << 24;

    private final IcdTree tree;
    private final long nowNanos;
    private final List<Warning> errors = new ArrayList<>();
    private final List<Warning> warnings = new ArrayList<>();

    private TimeFixProfileValidator(IcdTree tree, long nowNanos) {
        this.tree = tree;
        this.nowNanos = nowNanos;
    }

    /** @param nowNanos the current time, for "is this type wide enough for today's epoch values" */
    public static ProfileCheck check(List<MessageTimeFix> messages, IcdTree tree, long nowNanos) {
        TimeFixProfileValidator validator = new TimeFixProfileValidator(tree, nowNanos);
        Set<Long> opcodes = new HashSet<>();

        for (MessageTimeFix message : messages) {
            if (!opcodes.add(message.opcode())) {
                validator.errors.add(Warning.of("TIMEFIX_DUPLICATE_MESSAGE", "opcode", message.opcode()));
                continue;
            }
            validator.checkMessage(message);
        }
        return new ProfileCheck(validator.errors, validator.warnings);
    }

    private void checkMessage(MessageTimeFix fix) {
        IcdTreeMessage message = tree.message(fix.opcode());

        if (message == null) {
            errors.add(Warning.of("TIMEFIX_UNKNOWN_MESSAGE", "opcode", fix.opcode()));
            return;
        }
        List<TimeFieldRef> fields = fix.fields() == null ? List.of() : fix.fields();

        if (fix.base() == null) {
            if (!fields.isEmpty()) errors.add(Warning.of("TIMEFIX_NO_BASE", "message", message.name()));
            return;
        }
        Map<String, IcdTreeNode> nodes = message.byPath();
        IcdTreeNode base = checkField(message, nodes, fix.base());
        if (base != null && base.inArray()) errors.add(Warning.of("TIMEFIX_BASE_IN_ARRAY", "message", message.name(), "path", fix.base().path()));
        Set<String> seen = new HashSet<>();
        seen.add(fix.base().path());

        for (TimeFieldRef field : fields) {
            if (!seen.add(field.path())) {
                errors.add(Warning.of("TIMEFIX_DUPLICATE_FIELD", "message", message.name(), "path", field.path()));
                continue;
            }
            checkField(message, nodes, field);
        }
    }

    /** @return the field's node if it is usable */
    private IcdTreeNode checkField(IcdTreeMessage message, Map<String, IcdTreeNode> nodes, TimeFieldRef field) {
        IcdTreeNode node = field.path() == null ? null : nodes.get(field.path());

        if (node == null) {
            errors.add(Warning.of("TIMEFIX_UNKNOWN_FIELD", "message", message.name(), "path", String.valueOf(field.path())));
            return null;
        }

        if (!node.numeric()) {
            errors.add(Warning.of("TIMEFIX_NOT_NUMERIC", "message", message.name(), "path", field.path()));
            return null;
        }

        if (field.kind() == null) {
            errors.add(Warning.of("TIMEFIX_KIND_REQUIRED", "message", message.name(), "path", field.path()));
            return null;
        }
        checkWidth(message, node, field.kind());
        return node;
    }

    /** Warns when the type cannot hold the largest value the kind needs, or holds it only roughly. */
    private void checkWidth(IcdTreeMessage message, IcdTreeNode node, ETimeKind kind) {
        long needed = largestValue(kind);
        EFieldType type = node.storageType();

        if (type.floating()) {
            if (type == EFieldType.FLOAT32 && needed > FLOAT32_EXACT) {
                warnings.add(Warning.of("TIMEFIX_FLOAT_PRECISION", "message", message.name(), "path", node.path(), "kind", kind.name()));
            }
            return;
        }
        long max = node.bitWidth() > 0 ? (node.bitWidth() >= 63 ? Long.MAX_VALUE : (1L << node.bitWidth()) - 1) : type.maxValue();

        if (max < needed) {
            warnings.add(Warning.of("TIMEFIX_TYPE_TOO_SMALL", "message", message.name(), "path", node.path(), "kind", kind.name(),
                    "type", node.bitWidth() > 0 ? node.bitWidth() + " bits" : type.name()));
        }
    }

    /** The largest value a field of this kind holds: today's epoch value, or the end of a day. */
    private long largestValue(ETimeKind kind) {
        long nanos = kind.timeOfDay() ? TimeCodec.NANOS_PER_DAY - 1 : nowNanos;
        return nanos / kind.unitNanos();
    }
}
