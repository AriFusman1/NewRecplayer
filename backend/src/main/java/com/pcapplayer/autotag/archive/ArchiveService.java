package com.pcapplayer.autotag.archive;

import com.pcapplayer.errorHandling.ApiException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Browsing the archive and resolving a selection (files, or a folder with a time span) for a run. */
public class ArchiveService {

    /** How many files of a selection the preview lists by name. */
    private static final int PREVIEW_SAMPLE = 20;

    /**
     * What a selection would contain.
     *
     * @param sample the first files, to show the user
     */
    public record Preview(int count, long totalBytes, int maxFiles, boolean overLimit, List<ArchiveObject> sample) {
    }

    private final ArchiveStore store;
    private final int maxFiles;

    /** @param store null when no archive is configured */
    public ArchiveService(ArchiveStore store, int maxFiles) {
        this.store = store;
        this.maxFiles = maxFiles;
    }

    public boolean configured() {
        return store != null;
    }

    public ArchiveFolder browse(String prefix) {
        String clean = ArchivePaths.prefix(prefix);
        return call(() -> requireStore().browse(clean));
    }

    /** The objects under a folder, filtered by the span (null = no span), inclusive at both ends. */
    public List<ArchiveObject> selectFolder(String prefix, boolean recursive, TimeSpan span) {
        String clean = ArchivePaths.prefix(prefix);
        return ArchiveSelection.filter(call(() -> requireStore().list(clean, recursive)), span);
    }

    /** The chosen objects; an unknown key is a 404, an empty object is left out. */
    public List<ArchiveObject> selectFiles(List<String> keys) {
        List<ArchiveObject> objects = new ArrayList<>();

        for (String key : keys == null ? List.<String>of() : keys) {
            String clean = ArchivePaths.key(key);
            ArchiveObject object = call(() -> requireStore().head(clean));
            if (object == null) throw ApiException.notFound("ARCHIVE_NOT_FOUND", Map.of("key", clean), "No such archive file");
            if (ArchiveSelection.isFile(object)) objects.add(object);
        }
        return objects;
    }

    public Preview preview(List<ArchiveObject> objects) {
        long total = objects.stream().mapToLong(ArchiveObject::size).sum();
        return new Preview(objects.size(), total, maxFiles, objects.size() > maxFiles,
                objects.subList(0, Math.min(PREVIEW_SAMPLE, objects.size())));
    }

    public ArchiveStore store() {
        return requireStore();
    }

    private ArchiveStore requireStore() {
        if (store == null) throw ApiException.unprocessable("ARCHIVE_NOT_CONFIGURED", "No archive S3 is configured");
        return store;
    }

    private interface ArchiveCall<T> {
        T run() throws IOException;
    }

    private static <T> T call(ArchiveCall<T> work) {
        try {
            return work.run();
        } catch (IOException e) {
            throw ApiException.unavailable("ARCHIVE_ERROR", ApiException.reason(e), "The archive cannot be read");
        }
    }
}
