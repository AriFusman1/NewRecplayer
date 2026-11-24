package com.pcapplayer.icd.versions.sample;

import com.pcapplayer.icd.annotations.IcdField;
import com.pcapplayer.icd.annotations.IcdMessage;
import com.pcapplayer.icd.annotations.IcdPadding;
import com.pcapplayer.icd.annotations.IcdString;
import com.pcapplayer.icd.model.EByteOrder;
import com.pcapplayer.icd.model.EFieldType;

/** Opcode 1, 25 body bytes. Declared out of order on purpose: only {@code order} counts. */
@IcdMessage(opcode = 0x0001, name = "Status")
public class StatusMessage {

    /** Regular time in milliseconds. */
    @IcdField(order = 5)
    long timestampMs;

    @IcdField(order = 1)
    EStatusCode status;

    @IcdField(order = 2)
    StatusFlags flags;

    @IcdField(order = 3)
    @IcdPadding(bytes = 2)
    byte[] reserved;

    @IcdField(order = 4)
    @IcdString(length = 8)
    String unitName;

    @IcdField(order = 6, type = EFieldType.UINT32, byteOrder = EByteOrder.LITTLE_ENDIAN)
    long counter;

    @IcdField(order = 7)
    boolean healthy;
}
