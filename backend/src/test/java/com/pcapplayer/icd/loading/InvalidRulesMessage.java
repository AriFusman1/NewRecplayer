package com.pcapplayer.icd.loading;

import com.pcapplayer.icd.annotations.IcdArray;
import com.pcapplayer.icd.annotations.IcdField;
import com.pcapplayer.icd.annotations.IcdMessage;

/** The array's count field comes after the array. */
@IcdMessage(opcode = 1, name = "Invalid")
class InvalidRulesMessage {

    @IcdField(order = 1)
    @IcdArray(lengthField = "later")
    int[] items;

    @IcdField(order = 2)
    int later;
}
