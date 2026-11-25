package com.pcapplayer.icd.versions.sample;

import com.pcapplayer.icd.annotations.IcdBitfield;
import com.pcapplayer.icd.annotations.IcdBits;
import com.pcapplayer.icd.annotations.IcdField;
import com.pcapplayer.icd.model.EBitOrder;
import com.pcapplayer.icd.model.EFieldType;

/** One byte: ready is the top bit, then 3 bits of mode, then 4 spare bits. */
@IcdBitfield(type = EFieldType.UINT8, bitOrder = EBitOrder.MSB_FIRST)
public class StatusFlags {

    @IcdField(order = 1)
    @IcdBits(1)
    int ready;

    @IcdField(order = 3)
    @IcdBits(4)
    int spare;

    @IcdField(order = 2)
    @IcdBits(3)
    int mode;
}
