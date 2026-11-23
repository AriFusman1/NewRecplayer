package com.pcapplayer.api.routes;

import com.pcapplayer.api.Http;
import com.pcapplayer.catalog.model.PcapFile;
import com.pcapplayer.catalog.service.CatalogService;
import com.pcapplayer.catalog.service.PlaylistService;
import com.pcapplayer.catalog.service.RecordService;
import com.pcapplayer.catalog.service.TaxonomyService;
import com.pcapplayer.pcap.script.EConnectionMode;
import com.pcapplayer.storage.FileCache;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Records, files, categories, tags, saved playlists and history. */
public final class CatalogRoutes {

    record NameBody(String name) {
    }

    record CategoryBody(String name, EConnectionMode mode) {
    }

    record TagBody(String name, String categoryId) {
    }

    private CatalogRoutes() {
    }

    public static void mount(Router router, Http http, CatalogService catalog, FileCache cache) {
        router.get("/api/catalog").handler(context -> http.blocking(context, catalog::catalog));
        router.get("/api/history").handler(context -> http.blocking(context, catalog::history));
        mountRecords(router, http, catalog.records(), cache);
        mountTaxonomy(router, http, catalog.taxonomy());
        mountPlaylists(router, http, catalog.playlists());
    }

    private static void mountRecords(Router router, Http http, RecordService records, FileCache cache) {
        router.get("/api/files/:id/summary").handler(context -> http.blocking(context, () -> records.fileSummary(id(context))));
        router.post("/api/preview").handler(context -> http.blocking(context, () ->
                records.preview(Http.body(context, RecordService.Draft.class))));
        router.post("/api/records").handler(context -> http.blockingCreated(context, () ->
                records.create(Http.body(context, RecordService.CreateRequest.class))));
        router.put("/api/records/:id").handler(context -> http.blocking(context, () ->
                records.update(id(context), Http.body(context, RecordService.UpdateRequest.class))));
        router.delete("/api/records/:id").handler(context -> http.blockingVoid(context, () -> records.delete(id(context))));
        router.get("/api/records/:id/download").handler(context -> download(context, records, cache));
    }

    /** Streams the record's capture file as an attachment named like the original upload. */
    private static void download(RoutingContext context, RecordService records, FileCache cache) {
        context.vertx().executeBlocking(() -> {
            PcapFile file = records.fileOfRecord(id(context));
            return Map.entry(file.originalName(), cache.get(file.objectKey()));
        }, false).onComplete(result -> {
            if (result.failed()) {
                Http.error(context, result.cause());
                return;
            }
            String name = URLEncoder.encode(result.result().getKey(), StandardCharsets.UTF_8).replace("+", "%20");
            context.response()
                    .putHeader("content-type", "application/octet-stream")
                    .putHeader("content-disposition", "attachment; filename*=UTF-8''" + name)
                    .sendFile(result.result().getValue().toString());
        });
    }

    private static void mountTaxonomy(Router router, Http http, TaxonomyService taxonomy) {
        router.post("/api/categories").handler(context -> http.blockingCreated(context, () -> {
            CategoryBody body = Http.body(context, CategoryBody.class);
            return taxonomy.createCategory(body.name(), body.mode());
        }));
        router.put("/api/categories/:id").handler(context -> http.blocking(context, () ->
                taxonomy.renameCategory(id(context), Http.body(context, NameBody.class).name())));
        router.get("/api/categories/:id/usage").handler(context -> http.blocking(context, () ->
                Map.of("records", taxonomy.categoryUsage(id(context)))));
        router.delete("/api/categories/:id").handler(context -> http.blockingVoid(context, () -> taxonomy.deleteCategory(id(context))));

        router.post("/api/tags").handler(context -> http.blockingCreated(context, () -> {
            TagBody body = Http.body(context, TagBody.class);
            return taxonomy.createTag(body.name(), body.categoryId());
        }));
        router.put("/api/tags/:id").handler(context -> http.blocking(context, () ->
                taxonomy.renameTag(id(context), Http.body(context, NameBody.class).name())));
        router.delete("/api/tags/:id").handler(context -> http.blockingVoid(context, () -> taxonomy.deleteTag(id(context))));
    }

    private static void mountPlaylists(Router router, Http http, PlaylistService playlists) {
        router.post("/api/playlists").handler(context -> http.blockingCreated(context, () ->
                playlists.create(Http.body(context, PlaylistService.PlaylistRequest.class))));
        router.put("/api/playlists/:id").handler(context -> http.blocking(context, () ->
                playlists.update(id(context), Http.body(context, PlaylistService.PlaylistRequest.class))));
        router.delete("/api/playlists/:id").handler(context -> http.blockingVoid(context, () -> playlists.delete(id(context))));
    }

    private static String id(RoutingContext context) {
        return context.pathParam("id");
    }
}
