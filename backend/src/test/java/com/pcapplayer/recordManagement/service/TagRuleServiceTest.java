package com.pcapplayer.recordManagement.service;

import com.pcapplayer.autotag.rules.EOperator;
import com.pcapplayer.autotag.rules.FieldPredicate;
import com.pcapplayer.autotag.rules.RuleCondition;
import com.pcapplayer.autotag.rules.RuleValue;
import com.pcapplayer.autotag.rules.TagRule;
import com.pcapplayer.errorHandling.ApiException;
import com.pcapplayer.icd.tree.IcdTreeBuilder;
import com.pcapplayer.pcapProcessing.diagnostics.Warning;
import com.pcapplayer.pcapProcessing.script.EConnectionMode;
import com.pcapplayer.recordManagement.model.Category;
import com.pcapplayer.testSupport.SampleIcdMessages;
import com.pcapplayer.testSupport.TestCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rule persistence per category, validation and ICD drift. */
class TagRuleServiceTest {

    @TempDir
    Path dir;

    private TestCatalog test;
    private TagRuleService rules;
    private Category category;

    @BeforeEach
    void setUp() throws IOException {
        test = new TestCatalog(dir, SampleIcdMessages.icd());
        rules = test.catalog.tagRules();
        category = test.catalog.taxonomy().createCategory("Radar", EConnectionMode.TCP_SERVER);
        test.catalog.icds().setCategoryIcd(category.id(), "sample-v1");
    }

    private static TagRule airRule(String name) {
        return new TagRule(null, name, List.of(RuleCondition.fields(2, 10,
                new FieldPredicate("targets[*].kind", EOperator.EQ, List.of(RuleValue.enumName("AIR"))))));
    }

    @Test
    void createUpdateDeleteAndReload() throws IOException {
        TagRule created = rules.create(category.id(), airRule("Air"));
        assertNotNull(created.id());
        assertEquals("AIR", created.conditions().get(0).predicates().get(0).values().get(0).name());

        rules.update(category.id(), created.id(), airRule("Air traffic"));
        TagRuleService reloaded = new TestCatalog(dir, SampleIcdMessages.icd()).catalog.tagRules();
        TagRuleService.RulesView view = reloaded.get(category.id());
        assertEquals(List.of("Air traffic"), view.rules().stream().map(TagRule::name).toList());
        assertEquals("sample-v1", view.icdId());
        assertTrue(view.warnings().isEmpty());

        rules.delete(category.id(), created.id());
        assertTrue(rules.get(category.id()).rules().isEmpty());
        assertEquals(404, assertThrows(ApiException.class, () -> rules.delete(category.id(), created.id())).status());
    }

    @Test
    void namesAreUniquePerCategory() {
        rules.create(category.id(), airRule("Air"));

        assertEquals("TAGRULE_NAME_EXISTS", assertThrows(ApiException.class, () -> rules.create(category.id(), airRule("air"))).code());
    }

    @Test
    void invalidRuleIsRejectedWithEveryError() {
        TagRule bad = new TagRule(null, "", List.of(RuleCondition.count(99, 0)));

        ApiException error = assertThrows(ApiException.class, () -> rules.create(category.id(), bad));

        assertEquals(422, error.status());
        @SuppressWarnings("unchecked")
        List<Warning> errors = (List<Warning>) error.params().get("errors");
        assertEquals(List.of("TAGRULE_NAME_REQUIRED", "TAGRULE_MIN_COUNT", "TAGRULE_UNKNOWN_OPCODE"), errors.stream().map(Warning::code).toList());
    }

    @Test
    void categoryWithoutIcdCannotHaveRules() {
        Category plain = test.catalog.taxonomy().createCategory("Plain", EConnectionMode.UDP_SERVER);

        assertEquals("NO_ICD", assertThrows(ApiException.class, () -> rules.create(plain.id(), airRule("x"))).code());
    }

    @Test
    void anotherIcdIsReportedAndStaleRulesCannotRun() {
        TagRule rule = rules.create(category.id(), airRule("Air"));
        // a JSON ICD with the Status message only: the Track message (opcode 2) is gone
        String json = """
                {"id": "small-v1", "name": "Small", "byteOrder": "BIG_ENDIAN",
                 "header": {"lengthIncludesHeader": true, "fields": [
                   {"name": "opcode", "order": 1, "type": "UINT16", "role": "opcode"},
                   {"name": "length", "order": 2, "type": "UINT16", "role": "length"}]},
                 "messages": [{"opcode": 1, "name": "Ping", "fields": [{"name": "n", "order": 1, "type": "UINT8"}]}]}
                """;
        test.catalog.icds().upload(json.getBytes(StandardCharsets.UTF_8));
        test.catalog.icds().setCategoryIcd(category.id(), "small-v1");

        List<String> codes = rules.get(category.id()).warnings().stream().map(Warning::code).toList();
        assertEquals(List.of("TAGRULE_ICD_MISMATCH", "TAGRULE_UNKNOWN_OPCODE"), codes);

        var tree = IcdTreeBuilder.of(test.catalog.icds().find("small-v1"));
        assertEquals("TAGRULE_UNKNOWN_OPCODE", assertThrows(ApiException.class,
                () -> rules.rulesForRun(category.id(), List.of(rule.id()), tree)).code());
    }

    @Test
    void deletingTheCategoryDeletesItsRules() throws IOException {
        rules.create(category.id(), airRule("Air"));

        test.catalog.taxonomy().deleteCategory(category.id(), null);

        assertEquals(null, test.repo.tagRules(category.id()));
    }
}
