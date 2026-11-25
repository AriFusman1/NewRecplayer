package com.pcapplayer.recordManagement.service;

import com.pcapplayer.autotag.rules.CategoryTagRules;
import com.pcapplayer.autotag.rules.RuleCheck;
import com.pcapplayer.autotag.rules.RuleValidator;
import com.pcapplayer.autotag.rules.TagRule;
import com.pcapplayer.errorHandling.ApiException;
import com.pcapplayer.icd.model.IcdDefinition;
import com.pcapplayer.icd.tree.IcdTree;
import com.pcapplayer.icd.tree.IcdTreeBuilder;
import com.pcapplayer.pcapProcessing.diagnostics.Warning;
import com.pcapplayer.recordManagement.model.Category;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.pcapplayer.recordManagement.service.CatalogSupport.writeToStorage;

/**
 * Auto-tagging rules, saved per category like the time-fix profile. A rule is checked against the
 * category's current ICD when it is saved, when the rules are shown, and when a run starts: if the
 * ICD changed and a rule's opcode or path is gone, the rule is reported and cannot be run until edited.
 */
public class TagRuleService {

    /**
     * @param warnings TAGRULE_ICD_MISMATCH when the rules were saved for another ICD, and every problem of
     *                 every rule against the current ICD (each names its rule)
     */
    public record RulesView(String categoryId, String icdId, List<TagRule> rules, IcdTree tree, List<Warning> warnings) {
    }

    private final CatalogSupport catalog;
    private final IcdService icds;
    private final Clock clock;

    TagRuleService(CatalogSupport catalog, IcdService icds, Clock clock) {
        this.catalog = catalog;
        this.icds = icds;
        this.clock = clock;
    }

    public RulesView get(String categoryId) {
        Category category = catalog.requireCategory(categoryId);
        CategoryTagRules saved = catalog.repo.tagRules(categoryId);
        List<TagRule> rules = saved == null ? List.of() : saved.rules();
        IcdDefinition icd = icds.find(category.icdId());
        IcdTree tree = icd == null ? null : IcdTreeBuilder.of(icd);
        List<Warning> warnings = new ArrayList<>();

        if (saved != null && !Objects.equals(saved.icdId(), category.icdId())) {
            warnings.add(Warning.of("TAGRULE_ICD_MISMATCH", "rulesIcd", String.valueOf(saved.icdId()),
                    "categoryIcd", String.valueOf(category.icdId())));
        }
        if (tree != null) for (TagRule rule : rules) warnings.addAll(RuleValidator.check(rule, tree).errors());
        return new RulesView(categoryId, category.icdId(), rules, tree, warnings);
    }

    /** Adds a rule; returns it with its new id. */
    public TagRule create(String categoryId, TagRule rule) {
        return save(categoryId, null, rule);
    }

    /** Replaces the rule with this id. */
    public TagRule update(String categoryId, String ruleId, TagRule rule) {
        return save(categoryId, ruleId, rule);
    }

    public void delete(String categoryId, String ruleId) {
        catalog.mutate(() -> {
            Category category = catalog.requireCategory(categoryId);
            List<TagRule> rules = currentRules(categoryId);
            if (!rules.removeIf(rule -> rule.id().equals(ruleId))) throw notFound(ruleId);
            put(category, rules);
        });
    }

    /**
     * The rules a run uses, checked against the ICD it will decode with.
     *
     * @throws ApiException 422 TAGRULE_NONE_SELECTED, 404 for an unknown id, or 422 with the errors of a stale rule
     */
    public List<TagRule> rulesForRun(String categoryId, List<String> ruleIds, IcdTree tree) {
        if (ruleIds == null || ruleIds.isEmpty()) throw ApiException.unprocessable("TAGRULE_NONE_SELECTED", "Choose at least one rule");
        List<TagRule> all = currentRules(categoryId);
        List<TagRule> chosen = new ArrayList<>();
        List<Warning> errors = new ArrayList<>();

        for (String id : ruleIds) {
            TagRule rule = all.stream().filter(candidate -> candidate.id().equals(id)).findFirst().orElseThrow(() -> notFound(id));
            RuleCheck check = RuleValidator.check(rule, tree);
            errors.addAll(check.errors());
            chosen.add(check.rule());
        }
        if (!errors.isEmpty()) throw CatalogSupport.validationFailed(errors);
        return chosen;
    }

    private TagRule save(String categoryId, String ruleId, TagRule rule) {
        Category category = catalog.requireCategory(categoryId);
        IcdDefinition icd = icds.find(category.icdId());
        if (icd == null) throw ApiException.unprocessable("NO_ICD", Map.of("category", category.name()), "The category has no ICD");

        RuleCheck check = RuleValidator.check(rule, IcdTreeBuilder.of(icd));
        if (!check.valid()) throw CatalogSupport.validationFailed(check.errors());
        TagRule checked = check.rule();

        return catalog.mutate(() -> {
            Category current = catalog.requireCategory(categoryId);
            if (!Objects.equals(current.icdId(), icd.id())) throw ApiException.conflict("TAGRULE_ICD_CHANGED", Map.of(), "The category's ICD changed");
            List<TagRule> rules = currentRules(categoryId);
            boolean nameTaken = rules.stream().anyMatch(other -> other.name().equalsIgnoreCase(checked.name()) && !other.id().equals(ruleId));
            if (nameTaken) throw CatalogSupport.nameExists("TAGRULE_NAME_EXISTS", checked.name(), "A rule with this name exists");
            TagRule saved;

            if (ruleId == null) {
                saved = new TagRule(UUID.randomUUID().toString(), checked.name(), checked.conditions());
                rules.add(saved);
            } else {
                int index = indexOf(rules, ruleId);
                saved = new TagRule(ruleId, checked.name(), checked.conditions());
                rules.set(index, saved);
            }
            put(current, rules);
            return saved;
        });
    }

    private List<TagRule> currentRules(String categoryId) {
        CategoryTagRules saved = catalog.repo.tagRules(categoryId);
        return saved == null ? new ArrayList<>() : new ArrayList<>(saved.rules());
    }

    private void put(Category category, List<TagRule> rules) {
        CategoryTagRules updated = new CategoryTagRules(category.id(), category.icdId(), clock.millis(), List.copyOf(rules));
        writeToStorage(() -> catalog.repo.putTagRules(updated));
    }

    private static int indexOf(List<TagRule> rules, String ruleId) {
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).id().equals(ruleId)) return i;
        }
        throw notFound(ruleId);
    }

    private static ApiException notFound(String ruleId) {
        return ApiException.notFound("TAGRULE_NOT_FOUND", Map.of("id", String.valueOf(ruleId)), "Rule not found");
    }
}
