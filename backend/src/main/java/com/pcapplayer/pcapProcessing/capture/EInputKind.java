package com.pcapplayer.pcapProcessing.capture;

/** What an input file turned out to be once any gzip layer is removed. Decided by magic bytes only. */
public enum EInputKind {

    /** Classic libpcap format (micro- or nanosecond, either byte order). */
    PCAP,

    /** pcapng. */
    PCAPNG,

    /** Empty, or the content does not start with a pcap or pcapng magic number. */
    NOT_A_PCAP
}
