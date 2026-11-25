package com.pcapplayer.autotag.rules;

import java.math.BigDecimal;

/**
 * One value a predicate compares with. Which member is used depends on the field's type:
 * {@code number} for integers and floats, {@code name} + {@code number} for an enum (chosen by name,
 * matched by number), {@code bool} for a BOOL, {@code text} for a string.
 */
public record RuleValue(BigDecimal number, String name, Boolean bool, String text) {

    public static RuleValue number(long value) {
        return new RuleValue(BigDecimal.valueOf(value), null, null, null);
    }

    public static RuleValue number(double value) {
        return new RuleValue(BigDecimal.valueOf(value), null, null, null);
    }

    public static RuleValue enumName(String name) {
        return new RuleValue(null, name, null, null);
    }

    public static RuleValue bool(boolean value) {
        return new RuleValue(null, null, value, null);
    }

    public static RuleValue text(String value) {
        return new RuleValue(null, null, null, value);
    }

    RuleValue withNumber(BigDecimal value) {
        return new RuleValue(value, name, bool, text);
    }
}
