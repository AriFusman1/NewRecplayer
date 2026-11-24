package com.pcapplayer.icd.versions.sample;

import com.pcapplayer.icd.annotations.IcdArray;
import com.pcapplayer.icd.annotations.IcdField;
import com.pcapplayer.icd.annotations.IcdMessage;
import com.pcapplayer.icd.model.EByteOrder;
import com.pcapplayer.icd.model.EFieldType;

/** Opcode 2, little endian (except {@code epochSeconds}). */
@IcdMessage(opcode = 0x0002, name = "Track", byteOrder = EByteOrder.LITTLE_ENDIAN)
public class TrackMessage {

    /** GPS: 25 µs ticks since UTC midnight. */
    @IcdField(order = 1, type = EFieldType.UINT64)
    long gpsTime;

    /** Regular time in seconds, big endian inside a little-endian message. */
    @IcdField(order = 2, type = EFieldType.UINT32, byteOrder = EByteOrder.BIG_ENDIAN)
    long epochSeconds;

    /** LTime with a fraction. */
    @IcdField(order = 3)
    double ltimeExact;

    @IcdField(order = 4, type = EFieldType.UINT8)
    int targetCount;

    @IcdField(order = 5)
    @IcdArray(lengthField = "targetCount")
    Target[] targets;

    @IcdField(order = 6)
    @IcdArray(length = 3)
    short[] samples;

    @IcdField(order = 7)
    byte temperature;
}
