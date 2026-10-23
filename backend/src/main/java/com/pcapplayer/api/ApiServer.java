package com.pcapplayer.api;

import com.pcapplayer.catalog.CatalogRepository;
import com.pcapplayer.catalog.CatalogService;
import com.pcapplayer.catalog.UploadService;
import com.pcapplayer.replay.ReplayEngine;
import com.pcapplayer.storage.BlobStore;
import com.pcapplayer.storage.FileCache;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.StaticHandler;

import java.util.function.BooleanSupplier;

/** Wires the router: API routes, the WebSocket, health probes and the built frontend (SPA). */
public final class ApiServer {

    private static final long JSON_BODY_LIMIT = 16L * 1024 * 1024;

    public record Services(CatalogService catalog, CatalogRepository repo, UploadService uploads, FileCache cache,
                           ReplayEngine engine, LiveHub hub, BlobStore store, BooleanSupplier ready) {
    }

    private ApiServer() {
    }

    public static Future<HttpServer> start(Vertx vertx, String host, int port, Services s) {
        Router router = Router.router(vertx);
        Http http = new Http(vertx);

        HealthRoutes.mount(router, http, s.store(), s.ready());
        // Uploads stream their raw body to disk, so they are mounted before the JSON body handler.
        UploadRoutes.mount(router, http, s.uploads(), s.repo());
        router.route("/api/*").handler(BodyHandler.create(false).setBodyLimit(JSON_BODY_LIMIT));
        router.route("/api/*").handler(ctx -> {
            if (s.ready().getAsBoolean()) ctx.next();
            else Http.error(ctx, new com.pcapplayer.util.ApiException(503, "NOT_READY", "Server is starting (loading metadata)"));
        });
        CatalogRoutes.mount(router, http, s.catalog(), s.cache());
        ReplayRoutes.mount(router, http, s.engine());
        router.route("/api/*").handler(ctx -> Http.json(ctx, 404, java.util.Map.of("code", "NOT_FOUND", "message", "No such endpoint")));

        // Built frontend from the classpath (webroot/); unknown paths fall back to index.html (client-side routing).
        router.route().handler(StaticHandler.create("webroot").setCachingEnabled(true).setIndexPage("index.html"));
        router.get().handler(ctx -> ctx.response().putHeader("cache-control", "no-cache").sendFile("webroot/index.html")
                .onFailure(e -> ctx.response().setStatusCode(404).end("Frontend not built")));

        return vertx.createHttpServer(new HttpServerOptions().setHost(host).setPort(port)
                        .setCompressionSupported(true).setMaxWebSocketMessageSize(1 << 20))
                .webSocketHandler(ws -> {
                    if ("/ws".equals(ws.path())) s.hub().accept(ws);
                    else ws.reject();
                })
                .requestHandler(router)
                .listen();
    }
}
