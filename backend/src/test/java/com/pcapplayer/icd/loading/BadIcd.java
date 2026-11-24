package com.pcapplayer.icd.loading;

import com.pcapplayer.icd.annotations.Icd;
import com.pcapplayer.icd.versions.sample.SampleHeader;

/** Test ICD whose message class has annotation mistakes. */
@Icd(id = "bad", name = "Bad", header = SampleHeader.class, messages = BadMessage.class)
final class BadIcd {

    private BadIcd() {
    }
}
