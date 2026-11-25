package com.pcapplayer.icd.decode;

/** Why bytes of a flow were not decoded. In every case the bytes are still sent unchanged. */
public enum EDecodeProblem {

    /** A header with an opcode the ICD does not define. Its length is trusted: the message is skipped whole. */
    UNKNOWN_OPCODE,

    /** The length field is impossible (smaller than the header or above maxMessageBytes): resync at the next packet. */
    INVALID_LENGTH,

    /** The flow ended (or the capture has a gap) in the middle of a message. */
    TRUNCATED,

    /** The message's bytes do not match its definition (too short, a bad array count). */
    PARSE_ERROR
}
