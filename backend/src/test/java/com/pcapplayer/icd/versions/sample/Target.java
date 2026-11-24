package com.pcapplayer.icd.versions.sample;

import com.pcapplayer.icd.annotations.IcdField;
import com.pcapplayer.icd.model.EFieldType;

/** One target of a track: 17 bytes. */
public class Target {

    @IcdField(order = 1, type = EFieldType.UINT16)
    int id;

    @IcdField(order = 2)
    Position pos;

    /** GPS ticks in a UINT32 (a day is 3,456,000,000 ticks, so it fits). */
    @IcdField(order = 3, type = EFieldType.UINT32)
    long seenGps;

    @IcdField(order = 4)
    ETargetKind kind;
}
