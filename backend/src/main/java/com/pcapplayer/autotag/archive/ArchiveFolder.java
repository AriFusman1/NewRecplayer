package com.pcapplayer.autotag.archive;

import java.util.List;

/**
 * One level of the archive, for the browser.
 *
 * @param prefix  the folder ("" for the root, else ending in '/'), relative to the root prefix
 * @param folders sub-folders, each ending in '/'
 * @param files   objects directly in this folder
 */
public record ArchiveFolder(String prefix, List<String> folders, List<ArchiveObject> files) {
}
