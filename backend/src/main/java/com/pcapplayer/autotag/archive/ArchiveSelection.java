package com.pcapplayer.autotag.archive;

import java.util.List;

/**
 * Which archive objects a run takes: real files (not empty, not folder markers) whose LastModified
 * falls in the span (when there is one).
 */
public final class ArchiveSelection {

    private ArchiveSelection() {
    }

    /** @param span null = no time filter */
    public static List<ArchiveObject> filter(List<ArchiveObject> objects, TimeSpan span) {
        return objects.stream()
                .filter(ArchiveSelection::isFile)
                .filter(object -> span == null || span.contains(object.lastModifiedUs()))
                .toList();
    }

    /** Empty objects and "folder/" markers are not files. */
    public static boolean isFile(ArchiveObject object) {
        return object.size() > 0 && !object.key().endsWith("/");
    }
}
