package com.pcapplayer.recordManagement.service;

import com.pcapplayer.errorHandling.ApiException;
import com.pcapplayer.pcapProcessing.diagnostics.Warning;
import com.pcapplayer.recordManagement.model.Category;
import com.pcapplayer.recordManagement.model.PcapFile;
import com.pcapplayer.recordManagement.model.Record;
import com.pcapplayer.recordManagement.repository.CatalogRepository;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * What the catalog services share: the repository, one lock for check-then-write rules (name
 * uniqueness etc.), the change notification, lookups that answer 404, and error helpers.
 * <p>
 * All services lock on the repository, so their rules see a consistent catalog. Slow work (parsing
 * captures, uploading to storage) is done outside {@link #mutate}.
 */
public class CatalogSupport {

    @FunctionalInterface
    interface StorageWrite {
        void run() throws IOException;
    }

    final CatalogRepository repo;
    private final Runnable onChange;

    CatalogSupport(CatalogRepository repo, Runnable onChange) {
        this.repo = repo;
        this.onChange = onChange;
    }

    /** Runs {@code work} under the catalog lock, then tells viewers the catalog changed. */
    <T> T mutate(Supplier<T> work) {
        T result = repo.exclusive(work);
        onChange.run();
        return result;
    }

    void mutate(Runnable work) {
        mutate(() -> {
            work.run();
            return null;
        });
    }

    // ------------------------------------------------------------------ lookups (404 when missing)

    Record requireRecord(String id) {
        Record record = id == null ? null : repo.record(id);
        if (record == null) throw ApiException.notFound("Record");
        return record;
    }

    PcapFile requireFile(String id) {
        PcapFile file = id == null ? null : repo.file(id);
        if (file == null) throw ApiException.notFound("File");
        return file;
    }

    Category requireCategory(String id) {
        Category category = id == null ? null : repo.category(id);
        if (category == null) throw ApiException.notFound("Category");
        return category;
    }

    // ------------------------------------------------------------------ validation

    static String requireName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) throw ApiException.unprocessable("NAME_REQUIRED", "Name is required");
        return trimmed;
    }

    /** True if another item (not {@code exceptId}) already has this name, ignoring case. */
    static <T> boolean nameTaken(Collection<T> items, Function<T, String> name, Function<T, String> id,
                                 String candidate, String exceptId) {
        for (T item : items) {
            if (name.apply(item).equalsIgnoreCase(candidate) && !id.apply(item).equals(exceptId)) return true;
        }
        return false;
    }

    static ApiException nameExists(String code, String name, String message) {
        return ApiException.conflict(code, Map.of("name", name), message);
    }

    /** 422 carrying every problem; the first one's code is the answer's code. */
    static ApiException validationFailed(List<Warning> errors) {
        return ApiException.unprocessable(errors.get(0).code(), Map.of("errors", errors), "Validation failed");
    }

    // ------------------------------------------------------------------ storage errors

    /** Runs a repository write, turning storage failures into 503 STORAGE_ERROR. */
    static void writeToStorage(StorageWrite action) {
        try {
            action.run();
        } catch (IOException e) {
            throw storage(e);
        }
    }

    static ApiException storage(IOException e) {
        return storage(e, "Storage error");
    }

    static ApiException storage(IOException e, String message) {
        return ApiException.unavailable("STORAGE_ERROR", ApiException.reason(e), message);
    }
}
