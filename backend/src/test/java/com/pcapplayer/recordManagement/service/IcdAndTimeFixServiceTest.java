package com.pcapplayer.recordManagement.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pcapplayer.common.Json;
import com.pcapplayer.errorHandling.ApiException;
import com.pcapplayer.icd.loading.IcdProblem;
import com.pcapplayer.icd.profile.MessageTimeFix;
import com.pcapplayer.icd.profile.TimeFieldRef;
import com.pcapplayer.icd.profile.TimeFixProfile;
import com.pcapplayer.icd.timefix.ETimeKind;
import com.pcapplayer.pcapProcessing.diagnostics.Warning;
import com.pcapplayer.pcapProcessing.script.EConnectionMode;
import com.pcapplayer.recordManagement.model.Category;
import com.pcapplayer.testSupport.SampleIcdMessages;
import com.pcapplayer.testSupport.TestCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ICD upload, category mapping and time-fix profiles, stored and reloaded through the real repository. */
class IcdAndTimeFixServiceTest {

    @TempDir
    Path dir;

    private TestCatalog test;
    private IcdService icds;
    private TimeFixService timeFix;

    @BeforeEach
    void setUp() throws IOException {
        test = new TestCatalog(dir, SampleIcdMessages.icd()); // "sample-v1" is a Java ICD here
        icds = test.catalog.icds();
        timeFix = test.catalog.timeFix();
    }

    @Test
    void uploadListReplaceAndDelete() throws IOException {
        IcdService.IcdSummary uploaded = icds.upload(json("uploaded-v1", "Uploaded"));
        assertEquals(new IcdService.IcdSummary("uploaded-v1", "Uploaded", IcdService.ESource.JSON, 2, List.of()), uploaded);
        assertEquals(List.of("sample-v1", "uploaded-v1"), icds.list().stream().map(IcdService.IcdSummary::id).toList());
        assertEquals(IcdService.ESource.JAVA, icds.list().get(0).source());

        icds.upload(json("uploaded-v1", "Renamed"));
        assertEquals("Renamed", icds.find("uploaded-v1").name(), "same id replaces");
        assertEquals("Renamed", new TestCatalog(dir).catalog.icds().find("uploaded-v1").name(), "stored");

        icds.delete("uploaded-v1");
        assertNull(icds.find("uploaded-v1"));
        assertEquals(404, assertThrows(ApiException.class, () -> icds.delete("uploaded-v1")).status());
    }

    @Test
    void anInvalidUploadListsEveryProblem() {
        ApiException error = assertThrows(ApiException.class, () -> icds.upload("{\"id\": \"x\"}".getBytes()));

        assertEquals("ICD_INVALID", error.code());
        @SuppressWarnings("unchecked")
        List<IcdProblem> problems = (List<IcdProblem>) error.params().get("problems");
        assertTrue(problems.stream().anyMatch(problem -> problem.path().equals("$.name")));
        assertTrue(problems.stream().anyMatch(problem -> problem.path().equals("$.messages")));
    }

    @Test
    void javaIcdsAreReadOnly() {
        assertEquals("ICD_READ_ONLY", assertThrows(ApiException.class, () -> icds.upload(json("sample-v1", "Clash"))).code());
        assertEquals("ICD_READ_ONLY", assertThrows(ApiException.class, () -> icds.delete("sample-v1")).code());
    }

    @Test
    void categoriesMapToOneIcdAndAnIcdInUseCannotBeDeleted() throws IOException {
        icds.upload(json("uploaded-v1", "Uploaded"));
        Category radar = category("Radar");
        Category sonar = category("Sonar");

        icds.setCategoryIcd(radar.id(), "uploaded-v1");
        icds.setCategoryIcd(sonar.id(), "uploaded-v1");
        assertEquals(List.of(radar.id(), sonar.id()), icds.list().get(1).usedBy(), "one ICD, several categories");
        assertEquals("ICD_IN_USE", assertThrows(ApiException.class, () -> icds.delete("uploaded-v1")).code());
        assertEquals("ICD_NOT_FOUND", assertThrows(ApiException.class, () -> icds.setCategoryIcd(radar.id(), "nope")).code());

        icds.setCategoryIcd(radar.id(), null);
        icds.setCategoryIcd(sonar.id(), "  ");
        assertNull(test.repo.category(radar.id()).icdId());
        icds.delete("uploaded-v1");
    }

    @Test
    void aProfileIsSavedForTheCategorysIcdAndReloaded() throws IOException {
        Category radar = category("Radar");
        icds.setCategoryIcd(radar.id(), "sample-v1");

        TimeFixService.SaveResult saved = timeFix.save(radar.id(), request(statusFix()));

        assertEquals("sample-v1", saved.profile().icdId());
        assertEquals(TestCatalog.CLOCK.millis(), saved.profile().updatedAtMs());
        assertEquals(List.of(), saved.warnings());
        TimeFixService.TimeFixView view = new TestCatalog(dir, SampleIcdMessages.icd()).catalog.timeFix().get(radar.id());
        assertEquals(saved.profile(), view.profile(), "stored");
        assertNotNull(view.tree());
        assertEquals(List.of(), view.warnings());
        assertEquals(saved.profile(), timeFix.activeProfile(test.repo.category(radar.id())));
    }

    @Test
    void savingValidates() {
        Category radar = category("Radar");
        assertEquals("NO_ICD", assertThrows(ApiException.class, () -> timeFix.save(radar.id(), request(statusFix()))).code());
        icds.setCategoryIcd(radar.id(), "sample-v1");

        ApiException error = assertThrows(ApiException.class, () -> timeFix.save(radar.id(), request(
                new MessageTimeFix(1, null, List.of(new TimeFieldRef("timestampMs", ETimeKind.REGULAR_MILLIS))))));
        assertEquals("TIMEFIX_NO_BASE", error.code());
        assertNull(test.repo.profile(radar.id()), "nothing saved");

        TimeFixService.SaveResult withWarning = timeFix.save(radar.id(), request(new MessageTimeFix(2,
                new TimeFieldRef("gpsTime", ETimeKind.GPS), List.of(new TimeFieldRef("epochSeconds", ETimeKind.REGULAR_MILLIS)))));
        assertEquals(List.of("TIMEFIX_TYPE_TOO_SMALL"), withWarning.warnings().stream().map(Warning::code).toList());
    }

    @Test
    void changingTheCategorysIcdKeepsButDisablesTheProfile() throws IOException {
        icds.upload(json("uploaded-v1", "Uploaded"));
        Category radar = category("Radar");
        icds.setCategoryIcd(radar.id(), "sample-v1");
        timeFix.save(radar.id(), request(statusFix()));

        icds.setCategoryIcd(radar.id(), "uploaded-v1");

        TimeFixService.TimeFixView view = timeFix.get(radar.id());
        assertNotNull(view.profile());
        assertEquals(List.of("TIMEFIX_ICD_MISMATCH"), view.warnings().stream().map(Warning::code).toList());
        assertNull(timeFix.activeProfile(test.repo.category(radar.id())), "not used for playback");
    }

    @Test
    void deletingTheCategoryDeletesItsProfile() throws IOException {
        Category radar = category("Radar");
        icds.setCategoryIcd(radar.id(), "sample-v1");
        timeFix.save(radar.id(), request(statusFix()));

        test.catalog.taxonomy().deleteCategory(radar.id(), null);

        assertNull(test.repo.profile(radar.id()));
        assertTrue(test.store.list("timefix/").isEmpty());
    }

    @Test
    void emptyEntriesAreDropped() {
        Category radar = category("Radar");
        icds.setCategoryIcd(radar.id(), "sample-v1");

        TimeFixProfile profile = timeFix.save(radar.id(), request(new MessageTimeFix(2, null, null), statusFix())).profile();

        assertEquals(List.of(1L), profile.messages().stream().map(MessageTimeFix::opcode).toList());
        assertNull(timeFix.activeProfile(new Category("other", "Other", EConnectionMode.UDP_SERVER, 0, "sample-v1")));
    }

    private Category category(String name) {
        return test.catalog.taxonomy().createCategory(name, EConnectionMode.TCP_SERVER);
    }

    private static MessageTimeFix statusFix() {
        return new MessageTimeFix(1, new TimeFieldRef("header.sendTime", ETimeKind.LTIME),
                List.of(new TimeFieldRef("timestampMs", ETimeKind.REGULAR_MILLIS)));
    }

    private static TimeFixService.SaveRequest request(MessageTimeFix... messages) {
        return new TimeFixService.SaveRequest(List.of(messages));
    }

    /** The sample ICD's JSON with another id and name. */
    private static byte[] json(String id, String name) throws IOException {
        try (InputStream in = IcdAndTimeFixServiceTest.class.getResourceAsStream("/icd/sample-icd.json")) {
            ObjectNode root = (ObjectNode) Json.MAPPER.readTree(in);
            root.put("id", id).put("name", name);
            return Json.MAPPER.writeValueAsBytes(root);
        }
    }
}
