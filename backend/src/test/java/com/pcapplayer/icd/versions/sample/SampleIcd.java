package com.pcapplayer.icd.versions.sample;

import com.pcapplayer.icd.annotations.Icd;
import com.pcapplayer.icd.model.EByteOrder;

/**
 * A small ICD that uses every feature: nested structs, an array counted by another field, a fixed
 * array, a bitfield, enums, a string, padding, mixed byte order, signed and unsigned types, and time
 * fields of every kind (LTime in the header, regular seconds and ms, GPS, fractional LTime).
 * <p>
 * The same ICD in JSON is the example in {@code docs/icd-json-format.md}; the tests check they are equal.
 */
@Icd(id = "sample-v1", name = "Sample ICD v1", byteOrder = EByteOrder.BIG_ENDIAN,
        header = SampleHeader.class, messages = {TrackMessage.class, StatusMessage.class})
public final class SampleIcd {

    private SampleIcd() {
    }
}
