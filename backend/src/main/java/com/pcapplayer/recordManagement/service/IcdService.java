package com.pcapplayer.recordManagement.service;

import com.pcapplayer.errorHandling.ApiException;
import com.pcapplayer.icd.loading.IcdLoadException;
import com.pcapplayer.icd.loading.IcdRegistry;
import com.pcapplayer.icd.loading.JsonIcdLoader;
import com.pcapplayer.icd.model.IcdDefinition;
import com.pcapplayer.icd.tree.IcdTree;
import com.pcapplayer.icd.tree.IcdTreeBuilder;
import com.pcapplayer.recordManagement.model.Category;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.pcapplayer.recordManagement.service.CatalogSupport.writeToStorage;

/**
 * The ICDs the server knows and which category uses which.
 * <ul>
 *   <li>Java ICDs (listed in {@code BuiltInIcds}) are read-only.</li>
 *   <li>JSON ICDs are uploaded through the API and stored as uploaded; uploading the same id replaces it.</li>
 *   <li>Each category uses at most one ICD; one ICD can serve several categories.</li>
 * </ul>
 */
public class IcdService {

    private static final Logger LOG = Logger.getLogger(IcdService.class.getName());

    public enum ESource {
        JAVA,
        JSON
    }

    /** One ICD in the list. */
    public record IcdSummary(String id, String name, ESource source, int messageCount, List<String> usedBy) {
    }

    /** A parsed JSON ICD and the bytes it was parsed from (to notice a replaced file). */
    private record Parsed(byte[] source, IcdDefinition definition) {
    }

    private final CatalogSupport catalog;
    private final IcdRegistry builtIns;
    private final Map<String, Parsed> parsed = new ConcurrentHashMap<>();

    IcdService(CatalogSupport catalog, IcdRegistry builtIns) {
        this.catalog = catalog;
        this.builtIns = builtIns;
    }

    /** The ICD with this id, or null. */
    public IcdDefinition find(String id) {
        if (id == null) return null;
        IcdDefinition builtIn = builtIns.find(id);
        if (builtIn != null) return builtIn;

        byte[] json = catalog.repo.icdJson(id);
        if (json == null) return null;
        Parsed cached = parsed.get(id);
        if (cached != null && cached.source() == json) return cached.definition();

        try {
            IcdDefinition definition = JsonIcdLoader.load(json);
            parsed.put(id, new Parsed(json, definition));
            return definition;
        } catch (IcdLoadException e) {
            LOG.log(Level.WARNING, "Stored ICD " + id + " is invalid: " + e.getMessage());
            return null;
        }
    }

    IcdDefinition require(String id) {
        IcdDefinition icd = find(id);
        if (icd == null) throw ApiException.notFound("ICD_NOT_FOUND", Map.of("id", String.valueOf(id)), "ICD not found");
        return icd;
    }

    /** Every ICD, sorted by id. */
    public List<IcdSummary> list() {
        List<IcdSummary> all = new ArrayList<>();
        for (IcdDefinition icd : builtIns.all()) all.add(summary(icd, ESource.JAVA));

        for (String id : catalog.repo.icdJson().keySet()) {
            IcdDefinition icd = find(id);
            if (icd != null && builtIns.find(id) == null) all.add(summary(icd, ESource.JSON));
        }
        all.sort(Comparator.comparing(IcdSummary::id));
        return all;
    }

    public IcdTree tree(String id) {
        return IcdTreeBuilder.of(require(id));
    }

    /** The raw JSON of an uploaded ICD (to download and edit it). */
    public byte[] json(String id) {
        byte[] json = catalog.repo.icdJson(id);
        if (json == null) throw ApiException.notFound("ICD_NOT_FOUND", Map.of("id", id), "No uploaded ICD with this id");
        return json;
    }

    /**
     * Adds an ICD, or replaces the uploaded one with the same id.
     *
     * @throws ApiException 422 ICD_INVALID with every problem ({@code params.problems}: [{path, message}])
     */
    public IcdSummary upload(byte[] json) {
        IcdDefinition icd;

        try {
            icd = JsonIcdLoader.load(json); // parse before taking the lock
        } catch (IcdLoadException e) {
            throw ApiException.unprocessable("ICD_INVALID", Map.of("problems", e.problems(), "count", e.problems().size()),
                    "Invalid ICD");
        }
        if (builtIns.find(icd.id()) != null) throw readOnly(icd.id());

        catalog.mutate(() -> writeToStorage(() -> catalog.repo.putIcdJson(icd.id(), json)));
        return summary(icd, ESource.JSON);
    }

    public void delete(String id) {
        if (builtIns.find(id) != null) throw readOnly(id);

        catalog.mutate(() -> {
            if (catalog.repo.icdJson(id) == null) throw ApiException.notFound("ICD_NOT_FOUND", Map.of("id", id), "ICD not found");
            List<String> users = categoriesUsing(id).stream().map(Category::name).toList();
            if (!users.isEmpty()) throw ApiException.conflict("ICD_IN_USE", Map.of("id", id, "categories", String.join(", ", users)), "ICD in use");
            writeToStorage(() -> catalog.repo.deleteIcdJson(id));
            parsed.remove(id);
        });
    }

    /** Sets (or with null, clears) the category's ICD. Its time-fix profile is kept. */
    public Category setCategoryIcd(String categoryId, String icdId) {
        String id = icdId == null || icdId.isBlank() ? null : icdId.trim();
        if (id != null) require(id);

        return catalog.mutate(() -> {
            Category old = catalog.requireCategory(categoryId);
            Category category = new Category(old.id(), old.name(), old.mode(), old.createdAtMs(), id);
            writeToStorage(() -> catalog.repo.putCategory(category));
            return category;
        });
    }

    private List<Category> categoriesUsing(String icdId) {
        return catalog.repo.categories().stream().filter(category -> icdId.equals(category.icdId())).toList();
    }

    private IcdSummary summary(IcdDefinition icd, ESource source) {
        List<String> usedBy = categoriesUsing(icd.id()).stream().map(Category::id).toList();
        return new IcdSummary(icd.id(), icd.name(), source, icd.messages().size(), usedBy);
    }

    private static ApiException readOnly(String id) {
        return ApiException.conflict("ICD_READ_ONLY", Map.of("id", id), "This ICD is defined in Java and cannot be changed here");
    }
}
