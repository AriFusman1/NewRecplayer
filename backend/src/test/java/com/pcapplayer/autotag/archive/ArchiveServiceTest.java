package com.pcapplayer.autotag.archive;

import com.pcapplayer.errorHandling.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Listing and time-span filtering by LastModified. */
class ArchiveServiceTest {

    private static final byte[] DATA = {1, 2, 3};

    private final InMemoryArchiveStore store = new InMemoryArchiveStore()
            .put("site/a.pcap", DATA, 100)
            .put("site/b.pcap.gz", DATA, 200)
            .put("site/c.pcap", DATA, 300)
            .put("site/empty.pcap", new byte[0], 200)
            .put("site/2026/d.pcap", DATA, 200)
            .put("other/e.pcap", DATA, 200);
    private final ArchiveService archive = new ArchiveService(store, 2);

    private static List<String> keys(List<ArchiveObject> objects) {
        return objects.stream().map(ArchiveObject::key).toList();
    }

    @Test
    void folderWithoutSubFolders() {
        assertEquals(List.of("site/a.pcap", "site/b.pcap.gz", "site/c.pcap"), keys(archive.selectFolder("site", false, null)),
                "the empty file and the sub-folder are left out");
    }

    @Test
    void folderWithSubFolders() {
        assertEquals(List.of("site/2026/d.pcap", "site/a.pcap", "site/b.pcap.gz", "site/c.pcap"), keys(archive.selectFolder("site/", true, null)));
    }

    @Test
    void spanBoundariesAreInclusive() {
        assertEquals(List.of("site/a.pcap", "site/b.pcap.gz"), keys(archive.selectFolder("site", false, new TimeSpan(100, 200))));
        assertEquals(List.of("site/b.pcap.gz", "site/c.pcap"), keys(archive.selectFolder("site", false, new TimeSpan(200, 300))));
        assertEquals(List.of("site/b.pcap.gz"), keys(archive.selectFolder("site", false, new TimeSpan(101, 299))));
        assertEquals(List.of(), keys(archive.selectFolder("site", false, new TimeSpan(301, 400))));
    }

    @Test
    void previewReportsTheLimit() {
        ArchiveService.Preview preview = archive.preview(archive.selectFolder("site", true, null));

        assertEquals(4, preview.count());
        assertEquals(12, preview.totalBytes());
        assertEquals(2, preview.maxFiles());
        assertTrue(preview.overLimit());
        assertFalse(archive.preview(archive.selectFiles(List.of("site/a.pcap"))).overLimit());
    }

    @Test
    void browseShowsFoldersAndFiles() {
        ArchiveFolder root = archive.browse("");
        assertEquals(List.of("other/", "site/"), root.folders());

        ArchiveFolder site = archive.browse("site/");
        assertEquals(List.of("site/2026/"), site.folders());
        assertEquals(3, site.files().size());
    }

    @Test
    void pathsCannotLeaveTheRoot() {
        assertEquals("ARCHIVE_BAD_PATH", assertThrows(ApiException.class, () -> archive.browse("../secret/")).code());
        assertEquals("ARCHIVE_BAD_PATH", assertThrows(ApiException.class, () -> archive.selectFiles(List.of("site/../../x"))).code());
        assertEquals("site/", ArchivePaths.prefix("/site"));
        assertEquals(404, assertThrows(ApiException.class, () -> archive.selectFiles(List.of("site/missing.pcap"))).status());
    }

    @Test
    void withoutAnArchiveEverythingIsRefused() {
        ArchiveService none = new ArchiveService(null, 10);

        assertFalse(none.configured());
        assertEquals("ARCHIVE_NOT_CONFIGURED", assertThrows(ApiException.class, () -> none.browse("")).code());
    }
}
