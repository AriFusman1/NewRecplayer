package com.pcapplayer.icd.loading.annotationJson;

/** The size of the bit chunk that {@code @Bits} fields are cut from ({@code BitfieldLength} in the legacy annotations). */
enum EBitfieldLength {
    BITS8(8),
    BITS16(16),
    BITS24(24),
    BITS32(32);

    final int bits;

    EBitfieldLength(int bits) {
        this.bits = bits;
    }
}
