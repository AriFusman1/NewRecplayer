package com.pcapplayer.web.routes;

import com.pcapplayer.autotag.archive.ArchiveService;
import com.pcapplayer.autotag.job.EFileStatus;
import com.pcapplayer.autotag.job.TagJobService;
import com.pcapplayer.autotag.rules.TagRule;
import com.pcapplayer.common.Strings;
import com.pcapplayer.errorHandling.ApiException;
import com.pcapplayer.recordManagement.service.TagRuleService;
import com.pcapplayer.web.Http;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

import java.nio.file.Path;

/**
 * Auto-tagging: rules per category ({@code /api/categories/{id}/tag-rules}), the archive browser
 * ({@code /api/archive/*}) and tagging jobs ({@code /api/tag-jobs}).
 */
public final class AutoTagRoutes {

    private AutoTagRoutes() {
    }

    /**
     * {@code POST /api/tag-jobs/{id}/files/{index}} with the raw file as the body, streamed to disk.
     * Mounted before the JSON body handler, like the regular upload.
     */
    public static void mountUploads(Router router, Http http, TagJobService jobs) {
        router.post("/api/tag-jobs/:id/files/:index").handler(context -> receive(context, http, jobs));
    }

    public static void mount(Router router, Http http, TagRuleService rules, TagJobService jobs, ArchiveService archive) {
        router.get("/api/categories/:id/tag-rules").handler(context -> http.blocking(context, () -> rules.get(id(context))));
        router.post("/api/categories/:id/tag-rules").handler(context -> http.blockingCreated(context, () ->
                rules.create(id(context), Http.body(context, TagRule.class))));
        router.put("/api/categories/:id/tag-rules/:ruleId").handler(context -> http.blocking(context, () ->
                rules.update(id(context), context.pathParam("ruleId"), Http.body(context, TagRule.class))));
        router.delete("/api/categories/:id/tag-rules/:ruleId").handler(context -> http.blockingVoid(context, () ->
                rules.delete(id(context), context.pathParam("ruleId"))));

        router.get("/api/auto-tag/capabilities").handler(context -> http.blocking(context, jobs::capabilities));
        router.get("/api/archive/browse").handler(context -> http.blocking(context, () ->
                archive.browse(context.queryParams().get("prefix"))));
        router.post("/api/archive/preview").handler(context -> http.blocking(context, () ->
                jobs.previewArchive(Http.body(context, TagJobService.CreateRequest.class))));

        router.get("/api/tag-jobs").handler(context -> http.blocking(context, jobs::list));
        router.post("/api/tag-jobs").handler(context -> http.blockingCreated(context, () ->
                jobs.create(Http.body(context, TagJobService.CreateRequest.class))));
        router.get("/api/tag-jobs/:id").handler(context -> http.blocking(context, () -> jobs.get(id(context), status(context))));
        router.post("/api/tag-jobs/:id/start").handler(context -> http.blocking(context, () -> jobs.start(id(context))));
        router.post("/api/tag-jobs/:id/cancel").handler(context -> http.blocking(context, () -> jobs.cancel(id(context))));
    }

    private static void receive(RoutingContext context, Http http, TagJobService jobs) {
        HttpServerRequest request = context.request();
        request.pause();
        String jobId = id(context);
        Path target;

        try {
            int index = Integer.parseInt(context.pathParam("index"));
            target = jobs.uploadTarget(jobId, index);
            context.vertx().fileSystem().open(target.toString(), new OpenOptions().setWrite(true).setCreate(true).setTruncateExisting(true))
                    .compose(file -> request.pipeTo(file))
                    .onSuccess(ignored -> http.blockingVoid(context, () -> jobs.fileReceived(jobId, index)))
                    .onFailure(e -> {
                        target.toFile().delete();
                        Http.error(context, e);
                    });
        } catch (NumberFormatException e) {
            request.resume();
            Http.error(context, ApiException.badRequest("TAGJOB_BAD_INDEX", "No such file in the job"));
        } catch (ApiException e) {
            request.resume();
            Http.error(context, e);
        }
    }

    private static EFileStatus status(RoutingContext context) {
        String status = context.queryParams().get("status");
        if (Strings.isBlank(status)) return null;

        try {
            return EFileStatus.valueOf(status.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("TAGJOB_BAD_STATUS", "Unknown file status " + status);
        }
    }

    private static String id(RoutingContext context) {
        return context.pathParam("id");
    }
}
