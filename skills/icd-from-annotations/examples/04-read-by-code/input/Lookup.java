package gw.messages;

import iaf.ofek.oryekarot.general.io.*;

/** name exists only when doesNameExist(bool) says so; tableName is read by a customizer. */
@BinaryData
public class Lookup {

    @Ordinal(0)
    public int code;

    @Ordinal(7)
    public int bool;

    @Ordinal(10)
    @ExistenceEncoded(method = "doesNameExist", params = "bool", isAbsolutePath = true)
    public int name;

    @Ordinal(11)
    @Custom(ReadString.class)
    public Byte[] tableName;

    public boolean doesNameExist(int bool) {
        return bool == 10;
    }
}
