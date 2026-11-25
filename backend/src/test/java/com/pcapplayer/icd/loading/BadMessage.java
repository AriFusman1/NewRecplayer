package com.pcapplayer.icd.loading;

import com.pcapplayer.icd.annotations.IcdField;
import com.pcapplayer.icd.annotations.IcdMessage;

/** A Java array without @IcdArray, a struct that contains itself, and a type the loader cannot map. */
@IcdMessage(opcode = 1, name = "Bad")
class BadMessage {

    @IcdField(order = 1)
    int[] values;

    @IcdField(order = 2)
    BadMessage self;

    @IcdField(order = 3)
    String unknown;
}
