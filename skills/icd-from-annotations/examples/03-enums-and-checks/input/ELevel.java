package gw.messages;

import iaf.ofek.oryekarot.general.io.IntValued;

/** Values that do not start at 0: IntValued gives each one. */
public enum ELevel implements IntValued {
    LOW(10),
    HIGH(20);

    private final int value;

    ELevel(int value) {
        this.value = value;
    }

    @Override
    public int intValue() {
        return value;
    }
}
