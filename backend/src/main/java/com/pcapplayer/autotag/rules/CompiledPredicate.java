package com.pcapplayer.autotag.rules;

import com.pcapplayer.icd.decode.DecodedMessage;
import com.pcapplayer.icd.decode.FieldValue;

import java.util.Map;

/**
 * A predicate ready to test messages.
 * <p>
 * <b>Arrays:</b> for a path through an array, the predicate is true when <i>any</i> element's value
 * passes. This holds for every operator, so {@code targets[*].kind NE AIR} means "some target is not
 * AIR", not "no target is AIR" (use {@code NOT_IN} on a single value, or a separate rule, for that).
 */
record CompiledPredicate(PathPattern path, ValueTest test) {

    boolean matches(DecodedMessage message) {
        Map<String, FieldValue> fields = message.fields();

        if (!path.hasWildcard()) {
            FieldValue value = fields.get(path.pattern());
            return value != null && test.test(value);
        }

        for (Map.Entry<String, FieldValue> field : fields.entrySet()) {
            if (path.matches(field.getKey()) && test.test(field.getValue())) return true;
        }
        return false;
    }
}
