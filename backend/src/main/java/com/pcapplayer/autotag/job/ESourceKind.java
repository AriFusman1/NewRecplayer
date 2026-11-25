package com.pcapplayer.autotag.job;

/** Where a job's files come from. */
public enum ESourceKind {

    /** Files chosen in the browser, uploaded to the job. */
    LOCAL_FILES,

    /** A folder chosen in the browser, filtered by lastModified in the browser, then uploaded. */
    LOCAL_FOLDER,

    /** Files chosen in the archive browser. */
    ARCHIVE_FILES,

    /** An archive folder, filtered by LastModified on the server. */
    ARCHIVE_FOLDER;

    public boolean local() {
        return this == LOCAL_FILES || this == LOCAL_FOLDER;
    }
}
