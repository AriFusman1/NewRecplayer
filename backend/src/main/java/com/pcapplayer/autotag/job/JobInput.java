package com.pcapplayer.autotag.job;

/**
 * One file of a job.
 *
 * @param path the archive key, or the path inside the chosen local folder (the name for chosen files)
 */
public record JobInput(String name, String path, long size) {
}
