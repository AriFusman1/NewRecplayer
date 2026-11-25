package gw.messages;

import iaf.ofek.oryekarot.general.io.*;

/** The example from the annotation document: a C struct {unsigned a:5; int b:3; char name[5];}. */
@BinaryData
public class Msg {

    public static final int sizeof = BinaryDataSerializer.sizeof(new Msg());

    @Ordinal(1)
    @Unsigned
    @Bits(value = 5, bitfield = BitfieldLength.BITS8)
    public int a;

    @Ordinal(2)
    @Bits(value = 3, bitfield = BitfieldLength.BITS8)
    public int b;

    @Ordinal(3)
    @Size(5)
    public String name;
}
