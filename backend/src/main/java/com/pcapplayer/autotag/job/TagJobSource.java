package com.pcapplayer.autotag.job;

/**
 * How the files of a job were chosen, for display.
 *
 * @param folder    the folder (local folder name or archive prefix); null for chosen files
 * @param recursive sub-folders included
 * @param fromUs    time span start (epoch µs, inclusive), or null
 * @param toUs      time span end (epoch µs, inclusive), or null
 */
public record TagJobSource(ESourceKind kind, String folder, boolean recursive, Long fromUs, Long toUs) {
}
