package com.pcapplayer.recordManagement.service;

import com.pcapplayer.errorHandling.ApiException;
import com.pcapplayer.icd.model.IcdDefinition;
import com.pcapplayer.icd.profile.MessageTimeFix;
import com.pcapplayer.icd.profile.ProfileCheck;
import com.pcapplayer.icd.profile.TimeFieldRef;
import com.pcapplayer.icd.profile.TimeFixProfile;
import com.pcapplayer.icd.profile.TimeFixProfileValidator;
import com.pcapplayer.icd.tree.IcdTree;
import com.pcapplayer.icd.tree.IcdTreeBuilder;
import com.pcapplayer.pcapProcessing.diagnostics.Warning;
import com.pcapplayer.recordManagement.model.Category;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.pcapplayer.recordManagement.service.CatalogSupport.writeToStorage;

/**
 * Time-fix profiles: per category, which time fields of which messages playback rewrites. A profile is
 * bound to the category's ICD; if the category's ICD changes, the profile is kept but flagged (and not
 * used for playback) until it is saved again.
 */
public class TimeFixService {

    /** What the time-fix screen needs in one call. */
    public record TimeFixView(String categoryId, String icdId, TimeFixProfile profile, IcdTree tree, List<Warning> warnings) {
    }

    /** The edited profile. */
    public record SaveRequest(List<MessageTimeFix> messages) {
    }

    /** The saved profile and the warnings about it. */
    public record SaveResult(TimeFixProfile profile, List<Warning> warnings) {
    }

    private final CatalogSupport catalog;
    private final IcdService icds;
    private final Clock clock;

    TimeFixService(CatalogSupport catalog, IcdService icds, Clock clock) {
        this.catalog = catalog;
        this.icds = icds;
        this.clock = clock;
    }

    public TimeFixView get(String categoryId) {
        Category category = catalog.requireCategory(categoryId);
        TimeFixProfile profile = catalog.repo.profile(categoryId);
        IcdDefinition icd = icds.find(category.icdId());
        IcdTree tree = icd == null ? null : IcdTreeBuilder.of(icd);
        List<Warning> warnings = new ArrayList<>();

        if (profile != null && !Objects.equals(profile.icdId(), category.icdId())) {
            warnings.add(Warning.of("TIMEFIX_ICD_MISMATCH", "profileIcd", String.valueOf(profile.icdId()),
                    "categoryIcd", String.valueOf(category.icdId())));
        } else if (profile != null && tree != null) {
            ProfileCheck check = TimeFixProfileValidator.check(profile.messages(), tree, nowNanos());
            warnings.addAll(check.errors()); // e.g. the ICD was replaced and a path disappeared
            warnings.addAll(check.warnings());
        }
        return new TimeFixView(categoryId, category.icdId(), profile, tree, warnings);
    }

    /**
     * Validates and saves the profile for the category's current ICD.
     *
     * @throws ApiException 422 with every error ({@code params.errors}); NO_ICD if the category has no ICD
     */
    public SaveResult save(String categoryId, SaveRequest request) {
        Category category = catalog.requireCategory(categoryId);
        IcdDefinition icd = icds.find(category.icdId());
        if (icd == null) throw ApiException.unprocessable("NO_ICD", Map.of("category", category.name()), "The category has no ICD");

        List<MessageTimeFix> messages = normalize(request == null ? null : request.messages());
        ProfileCheck check = TimeFixProfileValidator.check(messages, IcdTreeBuilder.of(icd), nowNanos());
        if (!check.errors().isEmpty()) throw CatalogSupport.validationFailed(check.errors());
        TimeFixProfile profile = new TimeFixProfile(categoryId, icd.id(), clock.millis(), messages);

        catalog.mutate(() -> {
            Category current = catalog.requireCategory(categoryId);
            if (!Objects.equals(current.icdId(), icd.id())) throw ApiException.conflict("TIMEFIX_ICD_CHANGED", Map.of(), "The category's ICD changed");
            writeToStorage(() -> catalog.repo.putProfile(profile));
        });
        return new SaveResult(profile, check.warnings());
    }

    /** The profile playback should use, or null: none saved, empty, or made for another ICD. */
    public TimeFixProfile activeProfile(Category category) {
        TimeFixProfile profile = catalog.repo.profile(category.id());
        if (profile == null || profile.messages().isEmpty() || !Objects.equals(profile.icdId(), category.icdId())) return null;
        return profile;
    }

    /** Drops entries with neither a base nor fields, and turns a missing field list into an empty one. */
    private static List<MessageTimeFix> normalize(List<MessageTimeFix> messages) {
        List<MessageTimeFix> result = new ArrayList<>();
        if (messages == null) return result;

        for (MessageTimeFix message : messages) {
            List<TimeFieldRef> fields = message.fields() == null ? List.of() : message.fields();
            if (message.base() == null && fields.isEmpty()) continue;
            result.add(new MessageTimeFix(message.opcode(), message.base(), fields));
        }
        return result;
    }

    private long nowNanos() {
        Instant now = clock.instant();
        return now.getEpochSecond() * 1_000_000_000L + now.getNano();
    }
}
