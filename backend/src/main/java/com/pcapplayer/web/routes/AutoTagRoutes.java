package com.pcapplayer.web.routes;

import com.pcapplayer.autotag.rules.TagRule;
import com.pcapplayer.recordManagement.service.TagRuleService;
import com.pcapplayer.web.Http;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

/**
 * Auto-tagging: rules per category ({@code /api/categories/{id}/tag-rules}), the archive browser
 * and tagging jobs.
 */
public final class AutoTagRoutes {

    private AutoTagRoutes() {
    }

    public static void mountRules(Router router, Http http, TagRuleService rules) {
        router.get("/api/categories/:id/tag-rules").handler(context -> http.blocking(context, () -> rules.get(id(context))));
        router.post("/api/categories/:id/tag-rules").handler(context -> http.blockingCreated(context, () ->
                rules.create(id(context), Http.body(context, TagRule.class))));
        router.put("/api/categories/:id/tag-rules/:ruleId").handler(context -> http.blocking(context, () ->
                rules.update(id(context), context.pathParam("ruleId"), Http.body(context, TagRule.class))));
        router.delete("/api/categories/:id/tag-rules/:ruleId").handler(context -> http.blockingVoid(context, () ->
                rules.delete(id(context), context.pathParam("ruleId"))));
    }

    private static String id(RoutingContext context) {
        return context.pathParam("id");
    }
}
