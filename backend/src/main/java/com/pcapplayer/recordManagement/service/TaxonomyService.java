package com.pcapplayer.recordManagement.service;

import com.pcapplayer.common.Strings;
import com.pcapplayer.errorHandling.ApiException;
import com.pcapplayer.pcapProcessing.script.EConnectionMode;
import com.pcapplayer.recordManagement.model.Category;
import com.pcapplayer.recordManagement.model.Tag;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static com.pcapplayer.recordManagement.service.CatalogSupport.nameExists;
import static com.pcapplayer.recordManagement.service.CatalogSupport.nameTaken;
import static com.pcapplayer.recordManagement.service.CatalogSupport.requireName;
import static com.pcapplayer.recordManagement.service.CatalogSupport.writeToStorage;

/** Categories (each with a fixed connection mode) and tags (global or scoped to one category). */
public class TaxonomyService {

    private final CatalogSupport catalog;
    private final RecordService records;

    TaxonomyService(CatalogSupport catalog, RecordService records) {
        this.catalog = catalog;
        this.records = records;
    }

    // ------------------------------------------------------------------ categories

    public Category createCategory(String name, EConnectionMode mode) {
        String trimmedName = requireName(name);
        if (mode == null) throw ApiException.unprocessable("MODE_REQUIRED", "Connection mode is required");

        return catalog.mutate(() -> {
            checkCategoryName(trimmedName, null);
            Category category = new Category(UUID.randomUUID().toString(), trimmedName, mode, System.currentTimeMillis());
            writeToStorage(() -> catalog.repo.putCategory(category));
            return category;
        });
    }

    /** Only the name can change; the mode is fixed. */
    public Category renameCategory(String id, String name) {
        String trimmedName = requireName(name);

        return catalog.mutate(() -> {
            Category old = catalog.requireCategory(id);
            checkCategoryName(trimmedName, id);
            Category category = new Category(id, trimmedName, old.mode(), old.createdAtMs());
            writeToStorage(() -> catalog.repo.putCategory(category));
            return category;
        });
    }

    public int categoryUsage(String id) {
        catalog.requireCategory(id);
        return records.countInCategory(id);
    }

    /** Deletes the category, all its records, its tags and any pcap files no longer used. */
    public void deleteCategory(String id) {
        catalog.mutate(() -> {
            catalog.requireCategory(id);
            records.deleteAllInCategory(id);
            List<Tag> remaining = catalog.repo.tags().stream().filter(tag -> !id.equals(tag.categoryId())).toList();

            writeToStorage(() -> {
                catalog.repo.saveTags(remaining);
                catalog.repo.deleteCategory(id);
            });
        });
    }

    private void checkCategoryName(String name, String exceptId) {
        if (nameTaken(catalog.repo.categories(), Category::name, Category::id, name, exceptId)) {
            throw nameExists("CATEGORY_EXISTS", name, "Category exists");
        }
    }

    // ------------------------------------------------------------------ tags

    public Tag createTag(String name, String categoryId) {
        String trimmedName = requireName(name);
        String scopeCategoryId = Strings.blankToNull(categoryId);

        return catalog.mutate(() -> {
            if (scopeCategoryId != null) catalog.requireCategory(scopeCategoryId);
            checkTagName(trimmedName, scopeCategoryId, null);
            Tag tag = new Tag(UUID.randomUUID().toString(), trimmedName, scopeCategoryId);
            List<Tag> all = catalog.repo.tags();
            all.add(tag);
            writeToStorage(() -> catalog.repo.saveTags(all));
            return tag;
        });
    }

    public Tag renameTag(String id, String name) {
        String trimmedName = requireName(name);

        return catalog.mutate(() -> {
            List<Tag> all = catalog.repo.tags();
            int i = indexOfTag(all, id);
            Tag old = all.get(i);
            checkTagName(trimmedName, old.categoryId(), id);
            Tag tag = new Tag(id, trimmedName, old.categoryId());
            all.set(i, tag);
            writeToStorage(() -> catalog.repo.saveTags(all));
            return tag;
        });
    }

    /** Deletes the tag and removes it from every record. */
    public void deleteTag(String id) {
        catalog.mutate(() -> {
            List<Tag> all = catalog.repo.tags();
            all.remove(indexOfTag(all, id));
            records.removeTagEverywhere(id);
            writeToStorage(() -> catalog.repo.saveTags(all));
        });
    }

    /** Tag names are unique within their scope: global tags among global ones, category tags within the category. */
    private void checkTagName(String name, String categoryId, String exceptId) {
        List<Tag> sameScope = catalog.repo.tags().stream()
                .filter(tag -> Objects.equals(tag.categoryId(), categoryId))
                .toList();

        if (nameTaken(sameScope, Tag::name, Tag::id, name, exceptId)) {
            throw nameExists("TAG_EXISTS", name, "Tag exists");
        }
    }

    private static int indexOfTag(List<Tag> all, String id) {
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id().equals(id)) return i;
        }
        throw ApiException.notFound("Tag");
    }
}
