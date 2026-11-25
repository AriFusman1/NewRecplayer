package gw.messages;

import iaf.ofek.oryekarot.general.io.*;

/** A report of several tracks. */
@BinaryData
public class TrackReport {

    @Ordinal(0)
    public int reportId;

    /** The size of tracks in bytes (not the number of tracks). */
    @Ordinal(4)
    @LenghtEncoder(isLengthInBytes = true)
    @BinaryInternal
    public int numOfReports;

    @Ordinal(8)
    @LengthEncoded(encodingField = "numOfReports ")
    public Track[] tracks;

    @Ordinal(9)
    @Size(4)
    public short[] spare;

    /** Its length is a byte written just before it. */
    @Ordinal(10)
    @LengthEncoded(value = 8, unsigned = true)
    public byte[] notes;
}
