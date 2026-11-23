package com.pcapplayer.api;

import com.pcapplayer.api.live.LiveHub;
import com.pcapplayer.api.routes.CatalogRoutes;
import com.pcapplayer.api.routes.HealthRoutes;
import com.pcapplayer.api.routes.ReplayRoutes;
import com.pcapplayer.api.routes.UploadRoutes;
import com.pcapplayer.catalog.service.CatalogService;
import com.pcapplayer.error.ApiException;
import com.pcapplayer.replay.ReplayEngine;
import com.pcapplayer.storage.BlobStore;
import com.pcapplayer.storage.FileCache;
import com.pcapplayer.upload.UploadService;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.StaticHandler;

import java.util.Map;
import java.util.function.BooleanSupplier;

/** Wires the router: API routes, the WebSocket, health probes and the built frontend (SPA). */
public class ApiServer {

    private static final long JSON_BODY_LIMIT = 16L * 1024 * 1024;

    public record Services(CatalogService catalog, UploadService uploads, FileCache cache,
                           ReplayEngine engine, LiveHub hub, BlobStore store, BooleanSupplier ready) {
    }

    private ApiServer() {
    }

    public static Future<HttpServer> start(Vertx vertx, String host, int port, Services services) {
        Router router = Router.router(vertx);
        Http http = new Http(vertx);

        HealthRoutes.mount(router, http, services.store(), services.ready());
        // Uploads stream their raw body to disk, so they are mounted before the JSON body handler.
        UploadRoutes.mount(router, http, services.uploads(), services.catalog().records());
        router.route("/api/*").handler(BodyHandler.create(false).setBodyLimit(JSON_BODY_LIMIT));
        router.route("/api/*").handler(context -> {
            if (services.ready().getAsBoolean()) context.next();
            else Http.error(context, ApiException.unavailable("NOT_READY", Map.of(), "Server is starting (loading metadata)"));
        });
        CatalogRoutes.mount(router, http, services.catalog(), services.cache());
        ReplayRoutes.mount(router, http, services.engine());
        router.route("/api/*").handler(context -> Http.json(context, Http.NOT_FOUND, Map.of("code", "NOT_FOUND", "message", "No such endpoint")));

        // Built frontend from the classpath (webroot/); unknown paths fall back to index.html (client-side routing).
        router.route().handler(StaticHandler.create("webroot").setCachingEnabled(true).setIndexPage("index.html"));
        router.get().handler(context -> context.response().putHeader("cache-control", "no-cache").sendFile("webroot/index.html")
                .onFailure(e -> context.response().setStatusCode(404).end("Frontend not built")));

        return vertx.createHttpServer(new HttpServerOptions().setHost(host).setPort(port)
                        .setCompressionSupported(true).setMaxWebSocketMessageSize(1 << 20))
                .webSocketHandler(socket -> {
                    if ("/ws".equals(socket.path())) services.hub().accept(socket);
                    else socket.reject();
                })
                .requestHandler(router)
                .listen();
    }
}
