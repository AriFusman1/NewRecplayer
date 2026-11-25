package com.pcapplayer.autotag.archive;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Read-only access to the archive of recordings. On purpose this is not a {@code BlobStore}: it has
 * no method that writes or deletes, so the archive cannot be changed by mistake.
 * <p>
 * Every key and prefix is relative to the archive's root prefix and has already been checked by
 * {@link ArchivePaths}.
 */
public interface ArchiveStore {

    /** The folders and files directly under {@code prefix}. */
    ArchiveFolder browse(String prefix) throws IOException;

    /** Every object under {@code prefix}: only directly in it, or also in sub-folders when {@code recursive}. */
    List<ArchiveObject> list(String prefix, boolean recursive) throws IOException;

    /** The object, or null if it does not exist. */
    ArchiveObject head(String key) throws IOException;

    /** Streams an object; the caller closes it. */
    InputStream open(String key) throws IOException;
}
