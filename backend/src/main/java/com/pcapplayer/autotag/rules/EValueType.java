package com.pcapplayer.autotag.rules;

import com.pcapplayer.icd.tree.IcdTreeNode;

import java.util.EnumSet;
import java.util.Set;

import static com.pcapplayer.autotag.rules.EOperator.BETWEEN;
import static com.pcapplayer.autotag.rules.EOperator.CONTAINS;
import static com.pcapplayer.autotag.rules.EOperator.EQ;
import static com.pcapplayer.autotag.rules.EOperator.GE;
import static com.pcapplayer.autotag.rules.EOperator.GT;
import static com.pcapplayer.autotag.rules.EOperator.IN;
import static com.pcapplayer.autotag.rules.EOperator.LE;
import static com.pcapplayer.autotag.rules.EOperator.LT;
import static com.pcapplayer.autotag.rules.EOperator.NE;
import static com.pcapplayer.autotag.rules.EOperator.NOT_IN;
import static com.pcapplayer.autotag.rules.EOperator.STARTS_WITH;

/** How a leaf field of the ICD is compared in a rule, and which operators it accepts. */
public enum EValueType {

    /** Integers of every width, and bits of a bitfield. Compared exactly (UINT64 included). */
    INTEGER(EnumSet.of(EQ, NE, GT, GE, LT, LE, BETWEEN, IN, NOT_IN)),

    /** FLOAT32 / FLOAT64. Equality uses the configured tolerance; ordering is exact. */
    FLOAT(EnumSet.of(EQ, NE, GT, GE, LT, LE, BETWEEN, IN, NOT_IN)),

    /** Chosen by name, compared by the stored number. */
    ENUM(EnumSet.of(EQ, NE, IN, NOT_IN)),

    BOOL(EnumSet.of(EQ)),

    STRING(EnumSet.of(EQ, NE, CONTAINS, STARTS_WITH, IN, NOT_IN));

    private final Set<EOperator> operators;

    EValueType(Set<EOperator> operators) {
        this.operators = operators;
    }

    public boolean allows(EOperator operator) {
        return operators.contains(operator);
    }

    /** The value type of a tree node, or null when the node is not a single value (struct, header, padding, …). */
    public static EValueType of(IcdTreeNode node) {
        return switch (node.kind()) {
            case BIT -> INTEGER;
            case ENUM -> ENUM;
            case STRING -> STRING;
            case PRIMITIVE -> switch (node.storageType()) {
                case BOOL -> BOOL;
                case FLOAT32, FLOAT64 -> FLOAT;
                default -> INTEGER;
            };
            default -> null;
        };
    }
}
