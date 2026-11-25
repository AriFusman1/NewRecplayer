package com.pcapplayer.autotag.job;

import com.pcapplayer.autotag.archive.ArchiveService;
import com.pcapplayer.autotag.archive.InMemoryArchiveStore;
import com.pcapplayer.autotag.rules.EOperator;
import com.pcapplayer.autotag.rules.FieldPredicate;
import com.pcapplayer.autotag.rules.RuleCondition;
import com.pcapplayer.autotag.rules.RuleValue;
import com.pcapplayer.autotag.rules.TagRule;
import com.pcapplayer.errorHandling.ApiException;
import com.pcapplayer.pcapProcessing.extract.ExtractionSettings;
import com.pcapplayer.pcapProcessing.script.EConnectionMode;
import com.pcapplayer.recordManagement.model.Category;
import com.pcapplayer.recordManagement.model.Record;
import com.pcapplayer.recordManagement.model.Tag;
import com.pcapplayer.recordManagement.repository.CatalogRepository;
import com.pcapplayer.testSupport.CaptureFiles;
import com.pcapplayer.testSupport.Gzip;
import com.pcapplayer.testSupport.SampleIcdMessages;
import com.pcapplayer.testSupport.TestCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.pcapplayer.autotag.job.TaggingCaptures.concat;
import static com.pcapplayer.autotag.job.TaggingCaptures.statuses;
import static com.pcapplayer.autotag.job.TaggingCaptures.tcpCapture;
import static com.pcapplayer.autotag.job.TaggingCaptures.tracks;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whole jobs over synthetic captures of the sample ICD, from a fake archive and from local uploads,
 * with the real catalog on a temp folder.
 */
class TagJobServiceTest {

    private static final byte AIR = 1;
    private static final byte SURFACE = 2;
    private static final String RULE = "Air traffic";

    @TempDir
    Path dir;

    private TestCatalog test;
    private InMemoryArchiveStore archive;
    private Category category;
    private String ruleId;
    private Path work;

    @BeforeEach
    void setUp() throws IOException {
        CaptureFiles.assumeLibpcap(dir);
        test = new TestCatalog(dir, SampleIcdMessages.icd());
        category = test.catalog.taxonomy().createCategory("Radar", EConnectionMode.TCP_SERVER);
        test.catalog.icds().setCategoryIcd(category.id(), "sample-v1");
        // at least 10 Track messages with an AIR target
        ruleId = test.catalog.tagRules().create(category.id(), new TagRule(null, RULE, List.of(RuleCondition.fields(2, 10,
                new FieldPredicate("targets[*].kind", EOperator.EQ, List.of(RuleValue.enumName("AIR"))))))).id();
        archive = new InMemoryArchiveStore();
        work = dir.resolve("work");
    }

    private TagJobService service(int maxFiles, int workers) {
        return new TagJobService(test.repo, test.catalog, new ArchiveService(archive, maxFiles), ExtractionSettings.defaults(),
                new TagJobService.Settings(maxFiles, workers, 1, 1e-6, work, TimeUnit.MINUTES.toMillis(10)), Clock.systemUTC());
    }

    private static TagJobService.CreateRequest folderRequest(String ruleId, boolean evaluateOnly, String folder) {
        return new TagJobService.CreateRequest(null, List.of(ruleId), evaluateOnly, ESourceKind.ARCHIVE_FOLDER, null, null, folder,
                false, null, null, "tester");
    }

    private TagJobService.CreateRequest forCategory(TagJobService.CreateRequest request) {
        return new TagJobService.CreateRequest(category.id(), request.ruleIds(), request.evaluateOnly(), request.kind(), request.files(),
                request.keys(), request.folder(), request.recursive(), request.fromUs(), request.toUs(), request.createdBy());
    }

    private static TagJob await(TagJobService jobs, String id) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;

        while (System.currentTimeMillis() < deadline) {
            TagJob job = jobs.get(id, null);
            if (job.status().finished()) return job;
            Thread.sleep(20);
        }
        throw new AssertionError("job did not finish");
    }

    private static Map<String, FileResult> byName(TagJob job) {
        return job.results().stream().collect(Collectors.toMap(FileResult::name, result -> result));
    }

    private boolean workIsEmpty() throws IOException {
        Path jobs = work.resolve("tagjobs");
        if (!Files.exists(jobs)) return true;

        try (Stream<Path> files = Files.walk(jobs)) {
            return files.noneMatch(Files::isRegularFile);
        }
    }

    /** Fills the archive folder "batch/" with every kind of input. */
    private byte[] fillArchive() throws IOException {
        byte[] matching = tcpCapture(dir, concat(statuses(2), tracks(12, AIR, 1)));
        byte[] gzippedMatch = Gzip.gzip(tcpCapture(dir, tracks(10, AIR, 2)));
        byte[] notMatching = tcpCapture(dir, concat(tracks(20, SURFACE, 3), statuses(5)));
        byte[] gzipped = Gzip.gzip(tcpCapture(dir, tracks(3, AIR, 4)));
        archive.put("batch/a.pcap", matching, 1)
                .put("batch/b.pcap.gz", gzippedMatch, 1)
                .put("batch/c.pcap", notMatching, 1)
                .put("batch/notes.txt", "just some text".getBytes(StandardCharsets.US_ASCII), 1)
                .put("batch/corrupt.pcap.gz", Arrays.copyOf(gzipped, gzipped.length / 2), 1)
                .put("batch/same-as-a.pcap", matching, 1)
                .put("batch/empty.pcap", new byte[0], 1);
        return matching;
    }

    @Test
    void jobOverTheArchiveSavesMatchingFilesWithTheirStatistics() throws Exception {
        fillArchive();
        TagJobService jobs = service(500, 3);

        TagJob job = await(jobs, jobs.create(forCategory(folderRequest(ruleId, false, "batch"))).id());

        assertEquals(ETagJobStatus.DONE, job.status());
        assertEquals(6, job.filesTotal(), "the empty object is not a file");
        assertEquals(6, job.filesDone());
        Map<String, FileResult> results = byName(job);
        assertEquals(EFileStatus.NOT_MATCHED, results.get("c.pcap").status());
        assertEquals(25, results.get("c.pcap").totalMessages());
        assertEquals(EFileStatus.NOT_A_PCAP, results.get("notes.txt").status());
        assertEquals(EFileStatus.FAILED, results.get("corrupt.pcap.gz").status());
        assertTrue(results.get("corrupt.pcap.gz").gzipped());
        assertNotNull(results.get("corrupt.pcap.gz").error());

        FileResult gz = results.get("b.pcap.gz");
        assertEquals(EFileStatus.MATCHED_SAVED, gz.status());
        assertTrue(gz.gzipped());
        assertEquals(List.of(RULE), gz.matchedRules());

        // the same content twice: one new record, then that record is updated
        List<EFileStatus> twins = List.of(results.get("a.pcap").status(), results.get("same-as-a.pcap").status());
        assertTrue(twins.containsAll(List.of(EFileStatus.MATCHED_SAVED, EFileStatus.MATCHED_EXISTING_UPDATED)), twins.toString());
        assertEquals(results.get("a.pcap").recordIds(), results.get("same-as-a.pcap").recordIds());

        FileResult a = results.get("a.pcap");
        assertEquals(14, a.totalMessages());
        assertEquals(2, a.counts().size());
        assertEquals(2, a.counts().get(0).count(), "Status (opcode 1)");
        assertEquals(12, a.counts().get(1).count(), "Track (opcode 2)");

        List<Record> records = test.repo.records();
        assertEquals(2, records.size());
        Record recordA = test.repo.record(a.recordIds().get(0));
        assertTrue(List.of("a", "same-as-a").contains(recordA.name()), "named after whichever twin was saved first");
        assertEquals(category.id(), recordA.categoryId());
        assertEquals(List.of(TaggingCaptures.SERVER_PORT), recordA.ports());
        assertEquals(12, recordA.tagging().count(2));
        assertEquals(List.of(RULE), recordA.tagging().matchedRules());
        assertEquals(job.id(), recordA.tagging().jobId());
        assertEquals(TimeUnit.NANOSECONDS.toMicros(TaggingCaptures.messageNanos(2)), recordA.startUs(),
                "the record starts at the first matching message (after the two statuses)");
        Tag tag = test.repo.tags().stream().filter(candidate -> candidate.name().equals(RULE)).findFirst().orElseThrow();
        assertTrue(recordA.tagIds().contains(tag.id()), "the rule name is also a tag");
        assertEquals("b.pcap", test.repo.file(test.repo.record(gz.recordIds().get(0)).fileId()).originalName(),
                "stored decompressed under the name without .gz");

        assertTrue(workIsEmpty(), "temp files are deleted");
        TagJob stored = new CatalogRepository(test.store, 20) {{ load(); }}.tagJob(job.id());
        assertEquals(ETagJobStatus.DONE, stored.status());
        assertEquals(6, stored.results().size());
        assertEquals(1, jobs.get(job.id(), EFileStatus.NOT_A_PCAP).results().size(), "results filter by status");
    }

    @Test
    void evaluateOnlySavesNothing() throws Exception {
        fillArchive();
        TagJobService jobs = service(500, 2);

        TagJob job = await(jobs, jobs.create(forCategory(folderRequest(ruleId, true, "batch"))).id());

        assertEquals(EFileStatus.MATCHED, byName(job).get("a.pcap").status());
        assertEquals(EFileStatus.MATCHED, byName(job).get("b.pcap.gz").status());
        assertTrue(test.repo.records().isEmpty());
        assertTrue(test.repo.files().isEmpty());
    }

    @Test
    void selectionAboveTheLimitIsRefusedBeforeStarting() throws Exception {
        fillArchive();
        TagJobService jobs = service(3, 1);

        ApiException error = assertThrows(ApiException.class, () -> jobs.create(forCategory(folderRequest(ruleId, false, "batch"))));

        assertEquals("TAGJOB_TOO_MANY_FILES", error.code());
        assertEquals(6, error.params().get("count"));
        assertTrue(jobs.list().isEmpty());
    }

    @Test
    void localUploadsAreTaggedAndMissingUploadsFail() throws Exception {
        TagJobService jobs = service(500, 2);
        byte[] matching = tcpCapture(dir, tracks(11, AIR, 7));
        TagJobService.CreateRequest request = new TagJobService.CreateRequest(category.id(), List.of(ruleId), false, ESourceKind.LOCAL_FOLDER,
                List.of(new JobInput("x.pcap.gz", "day1/x.pcap.gz", 100), new JobInput("y.pcap", "day1/y.pcap", 100)), null, "captures",
                true, 0L, Long.MAX_VALUE, "tester");

        TagJob created = jobs.create(request);
        assertEquals(ETagJobStatus.UPLOADING, created.status());
        Files.write(jobs.uploadTarget(created.id(), 0), Gzip.gzip(matching));
        jobs.fileReceived(created.id(), 0);
        jobs.start(created.id());
        TagJob job = await(jobs, created.id());

        assertEquals(EFileStatus.MATCHED_SAVED, byName(job).get("x.pcap.gz").status());
        assertEquals("day1/x.pcap.gz", byName(job).get("x.pcap.gz").path());
        assertEquals(EFileStatus.FAILED, byName(job).get("y.pcap").status());
        assertEquals(1, test.repo.records().size());
        assertTrue(workIsEmpty());
    }

    @Test
    void cancelStopsTheJobAndKeepsResultsSoFar() throws Exception {
        byte[] capture = tcpCapture(dir, tracks(10, AIR, 9));
        for (int i = 0; i < 20; i++) archive.put("slow/f" + i + ".pcap", capture, 1);
        archive.readDelayMs = 150;
        TagJobService jobs = service(500, 1);

        TagJob created = jobs.create(forCategory(folderRequest(ruleId, true, "slow")));
        long deadline = System.currentTimeMillis() + 30_000;
        while (jobs.get(created.id(), null).filesDone() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10);
        jobs.cancel(created.id());
        TagJob job = await(jobs, created.id());

        assertEquals(ETagJobStatus.CANCELLED, job.status());
        assertTrue(job.filesDone() >= 2 && job.filesDone() < 20, "done: " + job.filesDone());
        assertEquals(job.filesDone(), job.results().size());
        assertTrue(archive.opened.get() < 20, "files after the cancel are not read");
        assertTrue(workIsEmpty());
    }

    @Test
    void cancellingAWaitingJobEndsItAtOnce() {
        TagJobService jobs = service(500, 1);
        TagJobService.CreateRequest request = new TagJobService.CreateRequest(category.id(), List.of(ruleId), false, ESourceKind.LOCAL_FILES,
                List.of(new JobInput("x.pcap", null, 10)), null, null, false, null, null, "tester");
        TagJob created = jobs.create(request);

        TagJob cancelled = jobs.cancel(created.id());

        assertEquals(ETagJobStatus.CANCELLED, cancelled.status());
        assertEquals(ETagJobStatus.CANCELLED, test.repo.tagJob(created.id()).status());
    }

    @Test
    void unfinishedJobsFailAfterARestart() throws Exception {
        TagJobService before = service(500, 1);
        TagJobService.CreateRequest request = new TagJobService.CreateRequest(category.id(), List.of(ruleId), false, ESourceKind.LOCAL_FILES,
                List.of(new JobInput("x.pcap", null, 10)), null, null, false, null, null, "tester");
        String id = before.create(request).id();
        assertTrue(Files.exists(work.resolve("tagjobs").resolve(id)));

        TestCatalog restarted = new TestCatalog(dir, SampleIcdMessages.icd());
        TagJobService after = new TagJobService(restarted.repo, restarted.catalog, new ArchiveService(null, 500),
                ExtractionSettings.defaults(), new TagJobService.Settings(500, 1, 1, 1e-6, work, 60_000), Clock.systemUTC());
        after.recoverInterrupted();

        TagJob job = after.get(id, null);
        assertEquals(ETagJobStatus.FAILED, job.status());
        assertEquals(TagJobService.INTERRUPTED, job.failureReason());
        assertFalse(Files.exists(work.resolve("tagjobs")));
    }

    @Test
    void staleOrMissingRulesAreRefused() {
        TagJobService jobs = service(500, 1);

        TagJobService.CreateRequest noRules = new TagJobService.CreateRequest(category.id(), List.of(), false, ESourceKind.ARCHIVE_FOLDER,
                null, null, "x", false, null, null, "tester");
        assertEquals("TAGRULE_NONE_SELECTED", assertThrows(ApiException.class, () -> jobs.create(noRules)).code());
        assertEquals(404, assertThrows(ApiException.class,
                () -> jobs.create(forCategory(folderRequest("nope", false, "x")))).status());
        assertNull(test.repo.tagJob("nope"));
    }
}
