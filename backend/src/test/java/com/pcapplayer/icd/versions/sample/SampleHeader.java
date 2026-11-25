package com.pcapplayer.icd.versions.sample;

import com.pcapplayer.icd.annotations.IcdField;
import com.pcapplayer.icd.annotations.IcdHeader;
import com.pcapplayer.icd.annotations.Length;
import com.pcapplayer.icd.annotations.Opcode;
import com.pcapplayer.icd.model.EFieldType;

/** 12 bytes, big endian. The length counts the header. */
@IcdHeader(lengthIncludesHeader = true, maxMessageBytes = 4096)
public class SampleHeader {

    @IcdField(order = 1, type = EFieldType.UINT16)
    @Opcode
    int opcode;

    @IcdField(order = 2, type = EFieldType.UINT16)
    @Length
    int length;

    /** LTime: seconds since UTC midnight. */
    @IcdField(order = 4, type = EFieldType.UINT32)
    long sendTime;

    @IcdField(order = 3, type = EFieldType.UINT32)
    long sequence;
}
