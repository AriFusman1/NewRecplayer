package com.pcapplayer.api;

import com.pcapplayer.catalog.CatalogService;
import com.pcapplayer.catalog.PcapFile;
import com.pcapplayer.extract.ConnectionMode;
import com.pcapplayer.storage.FileCache;
import io.vertx.ext.web.Router;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Records, files, categories, tags and history. */
final class CatalogRoutes {

    record NameBody(String name) {
    }

    record CategoryBody(String name, ConnectionMode mode) {
    }

    record TagBody(String name, String categoryId) {
    }

    private CatalogRoutes() {
    }

    static void mount(Router r, Http http, CatalogService catalog, FileCache cache) {
        r.get("/api/catalog").handler(ctx -> http.blocking(ctx, catalog::catalog));
        r.get("/api/history").handler(ctx -> http.blocking(ctx, catalog::history));
        r.get("/api/files/:id/summary").handler(ctx -> http.blocking(ctx, () -> catalog.fileSummary(ctx.pathParam("id"))));

        r.post("/api/preview").handler(ctx -> http.blocking(ctx, () ->
                catalog.preview(Http.body(ctx, CatalogService.Draft.class))));
        r.post("/api/records").handler(ctx -> http.blocking(ctx, 201, () ->
                catalog.createRecord(Http.body(ctx, CatalogService.CreateRequest.class))));
        r.put("/api/records/:id").handler(ctx -> http.blocking(ctx, () ->
                catalog.updateRecord(ctx.pathParam("id"), Http.body(ctx, CatalogService.UpdateRequest.class))));
        r.delete("/api/records/:id").handler(ctx -> http.blocking(ctx, () -> {
            catalog.deleteRecord(ctx.pathParam("id"));
            return null;
        }));
        r.get("/api/records/:id/download").handler(ctx -> ctx.vertx().executeBlocking(() -> {
            PcapFile f = catalog.fileOfRecord(ctx.pathParam("id"));
            return Map.entry(f, cache.get(f.objectKey()));
        }, false).onComplete(ar -> {
            if (ar.failed()) {
                Http.error(ctx, ar.cause());
                return;
            }
            String name = ar.result().getKey().originalName();
            ctx.response()
                    .putHeader("content-type", "application/octet-stream")
                    .putHeader("content-disposition", "attachment; filename*=UTF-8''"
                            + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20"))
                    .sendFile(ar.result().getValue().toString());
        }));

        r.post("/api/categories").handler(ctx -> http.blocking(ctx, 201, () -> {
            CategoryBody b = Http.body(ctx, CategoryBody.class);
            return catalog.createCategory(b.name(), b.mode());
        }));
        r.put("/api/categories/:id").handler(ctx -> http.blocking(ctx, () ->
                catalog.renameCategory(ctx.pathParam("id"), Http.body(ctx, NameBody.class).name())));
        r.get("/api/categories/:id/usage").handler(ctx -> http.blocking(ctx, () ->
                Map.of("records", catalog.categoryUsage(ctx.pathParam("id")))));
        r.delete("/api/categories/:id").handler(ctx -> http.blocking(ctx, () -> {
            catalog.deleteCategory(ctx.pathParam("id"));
            return null;
        }));

        r.post("/api/tags").handler(ctx -> http.blocking(ctx, 201, () -> {
            TagBody b = Http.body(ctx, TagBody.class);
            return catalog.createTag(b.name(), b.categoryId());
        }));
        r.put("/api/tags/:id").handler(ctx -> http.blocking(ctx, () ->
                catalog.renameTag(ctx.pathParam("id"), Http.body(ctx, NameBody.class).name())));
        r.delete("/api/tags/:id").handler(ctx -> http.blocking(ctx, () -> {
            catalog.deleteTag(ctx.pathParam("id"));
            return null;
        }));
    }
}
