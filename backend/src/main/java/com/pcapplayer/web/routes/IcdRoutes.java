package com.pcapplayer.web.routes;

import com.pcapplayer.errorHandling.ApiException;
import com.pcapplayer.recordManagement.service.IcdService;
import com.pcapplayer.recordManagement.service.TimeFixService;
import com.pcapplayer.web.Http;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

/** ICDs, the category → ICD mapping and time-fix profiles. */
public final class IcdRoutes {

    record CategoryIcdBody(String icdId) {
    }

    private IcdRoutes() {
    }

    public static void mount(Router router, Http http, IcdService icds, TimeFixService timeFix) {
        router.get("/api/icds").handler(context -> http.blocking(context, icds::list));
        router.get("/api/icds/:id/tree").handler(context -> http.blocking(context, () -> icds.tree(id(context))));
        router.get("/api/icds/:id/json").handler(context -> downloadJson(context, http, icds));
        // the body is the ICD file itself
        router.post("/api/icds").handler(context -> http.blockingCreated(context, () -> icds.upload(rawBody(context))));
        router.delete("/api/icds/:id").handler(context -> http.blockingVoid(context, () -> icds.delete(id(context))));

        router.put("/api/categories/:id/icd").handler(context -> http.blocking(context, () ->
                icds.setCategoryIcd(id(context), Http.body(context, CategoryIcdBody.class).icdId())));
        router.get("/api/categories/:id/timefix").handler(context -> http.blocking(context, () -> timeFix.get(id(context))));
        router.put("/api/categories/:id/timefix").handler(context -> http.blocking(context, () ->
                timeFix.save(id(context), Http.body(context, TimeFixService.SaveRequest.class))));
    }

    /** The uploaded ICD file, as an attachment named after its id. */
    private static void downloadJson(RoutingContext context, Http http, IcdService icds) {
        context.vertx().executeBlocking(() -> icds.json(id(context)), false).onComplete(result -> {
            if (result.failed()) {
                Http.error(context, result.cause());
                return;
            }
            context.response()
                    .putHeader("content-type", "application/json")
                    .putHeader("content-disposition", "attachment; filename=\"" + id(context) + ".icd.json\"")
                    .end(Buffer.buffer(result.result()));
        });
    }

    private static byte[] rawBody(RoutingContext context) {
        if (context.body() == null || context.body().buffer() == null || context.body().length() == 0) {
            throw ApiException.badRequest("BODY_REQUIRED", "Send the ICD JSON file as the request body");
        }
        return context.body().buffer().getBytes();
    }

    private static String id(RoutingContext context) {
        return context.pathParam("id");
    }
}
