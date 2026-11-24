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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecordServiceTest {

    @TempDir
    Path dir;

    private CatalogFixture fixture;
    private RecordService records;
    private TaxonomyService taxonomy;

    @BeforeEach
    void setUp() throws IOException {
        fixture = new CatalogFixture(dir);
        records = fixture.catalog.records();
        taxonomy = fixture.catalog.taxonomy();
    }

    @Test
    void updateMovesTheRecordToACategoryWithTheSameMode() {
        Category radar = fixture.category("Radar", EConnectionMode.UDP_SERVER);
        Category sonar = fixture.category("Sonar", EConnectionMode.UDP_SERVER);
        Record record = fixture.record("r1", radar, List.of());

        Record updated = records.update(record.id(), fixture.update(record, sonar.id(), null));

        assertEquals(sonar.id(), updated.categoryId());
        assertEquals(sonar.id(), fixture.repo.record(record.id()).categoryId());
        assertEquals(record.stats().messageCount(), updated.stats().messageCount());
    }

    @Test
    void updateRejectsACategoryWithAnotherMode() {
        Category radar = fixture.category("Radar", EConnectionMode.UDP_SERVER);
        Category tcp = fixture.category("Tcp", EConnectionMode.TCP_SERVER);
        Record record = fixture.record("r1", radar, List.of());

        ApiException e = assertThrows(ApiException.class,
                () -> records.update(record.id(), fixture.update(record, tcp.id(), null)));

        assertEquals("CATEGORY_MODE_MISMATCH", e.code());
        assertEquals(422, e.status());
        assertEquals(radar.id(), fixture.repo.record(record.id()).categoryId());
    }

    @Test
    void updateRejectsAnUnknownCategory() {
        Category radar = fixture.category("Radar", EConnectionMode.UDP_SERVER);
        Record record = fixture.record("r1", radar, List.of());

        ApiException e = assertThrows(ApiException.class,
                () -> records.update(record.id(), fixture.update(record, "missing", null)));

        assertEquals(404, e.status());
    }

    @Test
    void updateReplacesTheTagsDroppingUnknownAndDuplicateIds() {
        Category radar = fixture.category("Radar", EConnectionMode.UDP_SERVER);
        Tag north = taxonomy.createTag("north");
        Tag south = taxonomy.createTag("south");
        Record record = fixture.record("r1", radar, List.of(north.id()));

        Record updated = records.update(record.id(),
                fixture.update(record, null, List.of(south.id(), "missing", south.id())));

        assertEquals(List.of(south.id()), updated.tagIds());
        assertEquals(radar.id(), updated.categoryId());
    }

    @Test
    void updateWithoutCategoryOrTagsKeepsThem() {
        Category radar = fixture.category("Radar", EConnectionMode.UDP_SERVER);
        Tag north = taxonomy.createTag("north");
        Record record = fixture.record("r1", radar, List.of(north.id()));

        Record updated = records.update(record.id(), fixture.update(record, null, null));

        assertEquals(radar.id(), updated.categoryId());
        assertEquals(List.of(north.id()), updated.tagIds());
    }

    @Test
    void updateCanClearAllTags() {
        Category radar = fixture.category("Radar", EConnectionMode.UDP_SERVER);
        Tag north = taxonomy.createTag("north");
        Record record = fixture.record("r1", radar, List.of(north.id()));

        Record updated = records.update(record.id(), fixture.update(record, null, List.of()));

        assertEquals(List.of(), updated.tagIds());
    }

    @Test
    void retagAddsAndRemovesTagsOnSeveralRecords() {
        Category radar = fixture.category("Radar", EConnectionMode.UDP_SERVER);
        Tag north = taxonomy.createTag("north");
        Tag south = taxonomy.createTag("south");
        Tag east = taxonomy.createTag("east");
        Record first = fixture.record("r1", radar, List.of(north.id()));
        Record second = fixture.record("r2", radar, List.of(south.id(), north.id()));
        Record untouched = fixture.record("r3", radar, List.of(north.id()));

        int changed = records.retag(new RecordService.RetagRequest(List.of(first.id(), second.id(), "deleted"),
                List.of(east.id(), "unknown"), List.of(north.id())));

        assertEquals(2, changed);
        assertEquals(List.of(east.id()), fixture.repo.record(first.id()).tagIds());
        assertEquals(List.of(south.id(), east.id()), fixture.repo.record(second.id()).tagIds());
        assertEquals(List.of(north.id()), fixture.repo.record(untouched.id()).tagIds());
    }

    @Test
    void retagCountsOnlyRecordsThatChanged() {
        Category radar = fixture.category("Radar", EConnectionMode.UDP_SERVER);
        Tag north = taxonomy.createTag("north");
        Record record = fixture.record("r1", radar, List.of(north.id()));

        assertEquals(0, records.retag(new RecordService.RetagRequest(List.of(record.id()), List.of(north.id()), null)));
    }

    @Test
    void retagIgnoresNullIds() {
        Category radar = fixture.category("Radar", EConnectionMode.UDP_SERVER);
        Tag north = taxonomy.createTag("north");
        Record record = fixture.record("r1", radar, List.of(north.id()));
        List<String> recordIds = new ArrayList<>();
        recordIds.add(null);
        recordIds.add(record.id());
        List<String> remove = new ArrayList<>();
        remove.add(null);
        remove.add(north.id());

        assertEquals(1, records.retag(new RecordService.RetagRequest(recordIds, null, remove)));
        assertEquals(List.of(), fixture.repo.record(record.id()).tagIds());
    }

    @Test
    void retagNeedsRecords() {
        assertEquals("RECORDS_REQUIRED", assertThrows(ApiException.class,
                () -> records.retag(new RecordService.RetagRequest(List.of(), List.of(), List.of()))).code());
    }
}
