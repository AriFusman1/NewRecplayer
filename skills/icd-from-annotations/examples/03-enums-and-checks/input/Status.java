package gw.messages;

import iaf.ofek.oryekarot.general.io.*;

/** The state of one sensor. */
@BinaryData
@Validated
@Charset(value = "ISO-8859-8", isReversed = true)
public class Status {

    @Ordinal(0)
    @Unsigned
    @Enumerable(byte.class)
    public EMode mode;

    @Ordinal(1)
    @Enumerable(short.class)
    public ELevel level;

    /** Degrees: the stored int times 0.1. */
    @Ordinal(2)
    @FixedPoint(lsb = 0.1, type = int.class)
    @ValidRange(minValue = 0, MaxValue = 360)
    public double azimuth;

    @Transient
    private boolean headValidity;

    @Ordinal(3)
    @InvalidityValue(200)
    public int head;

    /** Hebrew, stored last letter first. */
    @Ordinal(4)
    @Size(8)
    public String operator;

    @Ordinal(5)
    @Unsigned
    @Bits(bitfieldLength = BitfieldLength.BIT8, value = 7)
    public int priority;

    @Ordinal(6)
    @Bit
    public boolean alarm;
}
