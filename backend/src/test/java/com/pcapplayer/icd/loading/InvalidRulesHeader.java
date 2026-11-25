package com.pcapplayer.icd.loading;

import com.pcapplayer.icd.annotations.IcdField;
import com.pcapplayer.icd.annotations.IcdHeader;
import com.pcapplayer.icd.annotations.Length;
import com.pcapplayer.icd.model.EFieldType;

/** No @Opcode field. */
@IcdHeader
class InvalidRulesHeader {

    @IcdField(order = 1, type = EFieldType.UINT16)
    @Length
    int length;
}
