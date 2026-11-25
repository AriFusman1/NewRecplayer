package gw.messages;

import iaf.ofek.oryekarot.general.io.*;

/** One track. 10 bytes. */
@BinaryData
public class Track {

    @Ordinal(0)
    @Unsigned
    public int trackId;

    /** Range in meters: the stored int times 0.01. */
    @Ordinal(1)
    @FixedPoint(lsb = 0.01, type = int.class)
    public double range;

    @Ordinal(2)
    public float speed;
}
