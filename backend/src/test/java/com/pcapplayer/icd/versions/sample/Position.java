package com.pcapplayer.icd.versions.sample;

import com.pcapplayer.icd.annotations.IcdField;

/** 10 bytes. */
public class Position {

    @IcdField(order = 1)
    float lat;

    @IcdField(order = 2)
    float lon;

    @IcdField(order = 3)
    short alt;
}
