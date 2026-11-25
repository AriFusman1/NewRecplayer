package com.pcapplayer.icd.loading;

import com.pcapplayer.icd.annotations.Icd;

/** Test ICD whose annotations are well formed but break the model rules. */
@Icd(id = "invalid-rules", name = "Invalid rules", header = InvalidRulesHeader.class, messages = InvalidRulesMessage.class)
final class InvalidRulesIcd {

    private InvalidRulesIcd() {
    }
}
