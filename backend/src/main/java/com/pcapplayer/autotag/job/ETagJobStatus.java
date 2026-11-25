package com.pcapplayer.autotag.job;

/**
 * Lifecycle of a tagging job.
 * <pre>
 * PENDING ──(local files)──▶ UPLOADING ──▶ PENDING (queued) ──▶ RUNNING ──▶ DONE
 *    │                          │                                  ├──▶ CANCELLED  (by the user)
 *    └────────── cancel ────────┴──▶ CANCELLED                     └──▶ FAILED     (setup error, or "interrupted" by a restart)
 * </pre>
 */
public enum ETagJobStatus {
    PENDING,
    UPLOADING,
    RUNNING,
    DONE,
    CANCELLED,
    FAILED;

    public boolean finished() {
        return this == DONE || this == CANCELLED || this == FAILED;
    }
}
