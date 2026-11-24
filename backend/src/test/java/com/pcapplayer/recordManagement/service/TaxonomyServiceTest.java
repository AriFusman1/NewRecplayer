package com.pcapplayer.recordManagement.service;

import com.pcapplayer.errorHandling.ApiException;
import com.pcapplayer.pcapProcessing.script.EConnectionMode;
import com.pcapplayer.recordManagement.model.Category;
import com.pcapplayer.recordManagement.model.Record;
import com.pcapplayer.recordManagement.model.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaxonomyServiceTest {

    @TempDir
    Path dir;

    private CatalogFixture fixture;
    private TaxonomyService taxonomy;

    @BeforeEach
    void setUp() throws IOException {
        fixture = new CatalogFixture(dir);
        taxonomy = fixture.catalog.taxonomy();
    }

    @Test
    void createCategoryTrimsTheName() {
        Category category = taxonomy.createCategory("  Radar  ", EConnectionMode.UDP_SERVER);

        assertEquals("Radar", category.name());
        assertEquals(EConnectionMode.UDP_SERVER, fixture.repo.category(category.id()).mode());
    }

    @Test
    void categoryNamesAreUniqueIgnoringCaseAcrossModes() {
        taxonomy.createCategory("Radar", EConnectionMode.UDP_SERVER);

        ApiException e = assertThrows(ApiException.class, () -> taxonomy.createCategory("radar", EConnectionMode.TCP_SERVER));

        assertEquals("CATEGORY_EXISTS", e.code());
        assertEquals(409, e.status());
        assertEquals("radar", e.params().get("name"));
    }

    @Test
    void createCategoryRequiresANameAndAMode() {
        assertEquals("NAME_REQUIRED",
                assertThrows(ApiException.class, () -> taxonomy.createCategory("  ", EConnectionMode.UDP_SERVER)).code());
        assertEquals("MODE_REQUIRED", assertThrows(ApiException.class, () -> taxonomy.createCategory("Radar", null)).code());
    }

    @Test
    void tagNamesAreUniqueIgnoringCase() {
        taxonomy.createTag("North");

        ApiException e = assertThrows(ApiException.class, () -> taxonomy.createTag(" north "));

        assertEquals("TAG_EXISTS", e.code());
        assertEquals(409, e.status());
    }

    @Test
    void deletingATagRemovesItFromRecords() {
        Category radar = taxonomy.createCategory("Radar", EConnectionMode.UDP_SERVER);
        Tag north = taxonomy.createTag("north");
        Tag south = taxonomy.createTag("south");
        Record record = fixture.record("r1", radar, List.of(north.id(), south.id()));

        taxonomy.deleteTag(north.id());

        assertEquals(List.of(south.id()), fixture.repo.record(record.id()).tagIds());
    }

    @Test
    void deletingACategoryDeletesItsRecords() {
        Category radar = taxonomy.createCategory("Radar", EConnectionMode.UDP_SERVER);
        Record record = fixture.record("r1", radar, List.of());

        taxonomy.deleteCategory(radar.id(), null);

        assertNull(fixture.repo.record(record.id()));
        assertNull(fixture.repo.category(radar.id()));
    }

    @Test
    void aRecordMovedToANewCategoryCountsThere() {
        Category radar = taxonomy.createCategory("Radar", EConnectionMode.UDP_SERVER);
        Record record = fixture.record("r1", radar, List.of());
        Category sonar = taxonomy.createCategory("Sonar", EConnectionMode.UDP_SERVER);

        fixture.catalog.records().update(record.id(), fixture.update(record, sonar.id(), null));

        assertEquals(0, taxonomy.categoryUsage(radar.id()));
        assertEquals(1, taxonomy.categoryUsage(sonar.id()));
    }

    @Test
    void deletingACategoryCanMoveItsRecordsToOneWithTheSameMode() {
        Category radar = taxonomy.createCategory("Radar", EConnectionMode.UDP_SERVER);
        Category sonar = taxonomy.createCategory("Sonar", EConnectionMode.UDP_SERVER);
        Record first = fixture.record("r1", radar, List.of());
        Record second = fixture.record("r2", radar, List.of());

        taxonomy.deleteCategory(radar.id(), sonar.id());

        assertNull(fixture.repo.category(radar.id()));
        assertEquals(sonar.id(), fixture.repo.record(first.id()).categoryId());
        assertEquals(sonar.id(), fixture.repo.record(second.id()).categoryId());
        assertEquals(1, fixture.repo.files().size());
    }

    @Test
    void movingRecordsToACategoryWithAnotherModeIsRejectedAndDeletesNothing() {
        Category radar = taxonomy.createCategory("Radar", EConnectionMode.UDP_SERVER);
        Category web = taxonomy.createCategory("Web", EConnectionMode.TCP_SERVER);
        Record record = fixture.record("r1", radar, List.of());

        ApiException e = assertThrows(ApiException.class, () -> taxonomy.deleteCategory(radar.id(), web.id()));

        assertEquals("CATEGORY_MODE_MISMATCH", e.code());
        assertEquals(radar.id(), fixture.repo.record(record.id()).categoryId());
        assertEquals(radar, fixture.repo.category(radar.id()));
    }

    @Test
    void movingRecordsToTheDeletedCategoryItselfIsRejected() {
        Category radar = taxonomy.createCategory("Radar", EConnectionMode.UDP_SERVER);

        assertEquals("MOVE_TO_SAME", assertThrows(ApiException.class, () -> taxonomy.deleteCategory(radar.id(), radar.id())).code());
    }

    @Test
    void mergingATagReplacesItOnRecordsWithoutDuplicates() {
        Category radar = taxonomy.createCategory("Radar", EConnectionMode.UDP_SERVER);
        Tag north = taxonomy.createTag("north");
        Tag nord = taxonomy.createTag("nord");
        Tag south = taxonomy.createTag("south");
        Record both = fixture.record("r1", radar, List.of(nord.id(), south.id(), north.id()));
        Record onlyNord = fixture.record("r2", radar, List.of(nord.id()));

        Tag merged = taxonomy.mergeTag(nord.id(), north.id());

        assertEquals(north, merged);
        assertEquals(List.of(north.id(), south.id()), fixture.repo.record(both.id()).tagIds());
        assertEquals(List.of(north.id()), fixture.repo.record(onlyNord.id()).tagIds());
        assertEquals(List.of(north, south), fixture.repo.tags());
    }

    @Test
    void mergingATagIntoItselfOrAnUnknownTagIsRejected() {
        Tag north = taxonomy.createTag("north");

        assertEquals("MERGE_INTO_SAME", assertThrows(ApiException.class, () -> taxonomy.mergeTag(north.id(), north.id())).code());
        assertEquals(404, assertThrows(ApiException.class, () -> taxonomy.mergeTag(north.id(), "missing")).status());
        assertEquals(List.of(north), fixture.repo.tags());
    }
}
