package com.pcapplayer.icd.versions.sample;

import com.pcapplayer.icd.annotations.IcdEnum;
import com.pcapplayer.icd.annotations.IcdEnumValue;
import com.pcapplayer.icd.model.EFieldType;

/** Stored signed, to exercise a signed enum type. */
@IcdEnum(type = EFieldType.INT8)
public enum ETargetKind {
    @IcdEnumValue(-1) UNKNOWN,
    @IcdEnumValue(1) AIR,
    @IcdEnumValue(2) SURFACE
}
