package com.pcapplayer.autotag.job;

/** What happened to one file of a job. */
public enum EFileStatus {

    /** Matched at least one rule; a new record was created. */
    MATCHED_SAVED,

    /** Matched; records of the category already had this content (same SHA-256), so they were updated instead. */
    MATCHED_EXISTING_UPDATED,

    /** Matched, in an evaluate-only run: nothing was saved. */
    MATCHED,

    NOT_MATCHED,

    /** Empty, or not a pcap/pcapng once any gzip layer is removed. Not an error. */
    NOT_A_PCAP,

    /** Unreadable or corrupt input (or saving failed); see the error message. Parse problems never fail a file. */
    FAILED
}
