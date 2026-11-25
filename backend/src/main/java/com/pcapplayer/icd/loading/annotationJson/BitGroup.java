package com.pcapplayer.icd.loading.annotationJson;

import com.pcapplayer.icd.model.BitDef;
import com.pcapplayer.icd.model.BitfieldDef;
import com.pcapplayer.icd.model.EBitOrder;
import com.pcapplayer.icd.model.FieldDef;

import java.util.ArrayList;
import java.util.List;

/**
 * Consecutive {@code @Bits} / {@code @Bit} fields that share one bit chunk. The chunk is complete when
 * their widths add up to its length; it then becomes one flat bitfield, whose bits keep their own names.
 */
final class BitGroup {

    private final EBitfieldLength length;
    private final String path;
    private final List<BitDef> bits = new ArrayList<>();
    private int used;

    /** @param path JSON path of the first field, for problems about the whole group */
    BitGroup(EBitfieldLength length, String path) {
        this.length = length;
        this.path = path;
    }

    EBitfieldLength length() {
        return length;
    }

    String path() {
        return path;
    }

    /** @return false if the field does not fit in what is left of the chunk */
    boolean add(String name, int width) {
        if (used + width > length.bits) return false;

        bits.add(new BitDef(name, width));
        used += width;
        return true;
    }

    boolean full() {
        return used == length.bits;
    }

    int used() {
        return used;
    }

    FieldDef toField(int order, EBitOrder bitOrder) {
        String name = "bits_" + bits.get(0).name();
        BitfieldDef bitfield = new BitfieldDef(JavaTypes.ofBits(length.bits, true), bitOrder, List.copyOf(bits), true);
        return FieldDef.ofBitfield(name, order, bitfield);
    }
}
