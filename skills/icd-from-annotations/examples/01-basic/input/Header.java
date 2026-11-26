package gw.messages;

import iaf.ofek.oryekarot.general.io.*;

/** The header every message starts with. */
@BinaryData
public class Header {

    /** Which message follows. */
    @Ordinal(0)
    @Unsigned
    public int msgType;

    /** The whole message length in bytes, header included. */
    @Ordinal(1)
    @Unsigned
    public int msgLength;
}
