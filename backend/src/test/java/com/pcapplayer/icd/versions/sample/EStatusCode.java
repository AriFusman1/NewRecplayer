package com.pcapplayer.icd.versions.sample;

import com.pcapplayer.icd.annotations.IcdEnum;
import com.pcapplayer.icd.annotations.IcdEnumValue;
import com.pcapplayer.icd.model.EFieldType;

@IcdEnum(type = EFieldType.UINT8)
public enum EStatusCode {
    @IcdEnumValue(0) OFF,
    @IcdEnumValue(1) STANDBY,
    @IcdEnumValue(2) OPERATIONAL,
    @IcdEnumValue(255) FAULT
}
