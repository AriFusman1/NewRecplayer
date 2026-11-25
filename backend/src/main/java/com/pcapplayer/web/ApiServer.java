package com.pcapplayer.web;

import com.pcapplayer.autotag.archive.ArchiveService;
import com.pcapplayer.autotag.job.TagJobService;
import com.pcapplayer.errorHandling.ApiException;
import com.pcapplayer.persistence.BlobStore;
import com.pcapplayer.persistence.FileCache;
import com.pcapplayer.playback.ReplayEngine;
import com.pcapplayer.recordManagement.service.CatalogService;
import com.pcapplayer.uploadHandling.UploadService;
import com.pcapplayer.web.live.LiveHub;
import com.pcapplayer.web.routes.AutoTagRoutes;
import com.pcapplayer.web.routes.CatalogRoutes;
import com.pcapplayer.web.routes.HealthRoutes;
import com.pcapplayer.web.routes.IcdRoutes;
import com.pcapplayer.web.routes.ReplayRoutes;
import com.pcapplayer.web.routes.UploadRoutes;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.StaticHandler;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Wires the router: API routes, the WebSocket, health probes and the built frontend (SPA). */
public class ApiServer {

    private static final long JSON_BODY_LIMIT = 16L * 1024 * 1024;

    public record Services(CatalogService catalog, UploadService uploads, FileCache cache,
                           ReplayEngine engine, LiveHub hub, BlobStore store, BooleanSupplier ready,
                           TagJobService tagJobs, ArchiveService archive) {
    }

    private ApiServer() {
    }

    public static Future<HttpServer> start(Vertx vertx, String host, int port, Services services) {
        Router router = Router.router(vertx);
        Http http = new Http(vertx);

        HealthRoutes.mount(router, http, services.store(), services.ready());
        // Uploads stream their raw body to disk, so they are mounted before the JSON body handler.
        UploadRoutes.mount(router, http, services.uploads(), services.catalog().records());
        AutoTagRoutes.mountUploads(router, http, services.tagJobs());
        router.route("/api/*").handler(BodyHandler.create(false).setBodyLimit(JSON_BODY_LIMIT));

        router.route("/api/*").handler(context -> {
            if (services.ready().getAsBoolean()) context.next();
            else Http.error(context, ApiException.unavailable("NOT_READY", Map.of(), "Server is starting (loading metadata)"));
        });
        CatalogRoutes.mount(router, http, services.catalog(), services.cache());
        IcdRoutes.mount(router, http, services.catalog().icds(), services.catalog().timeFix());
        AutoTagRoutes.mount(router, http, services.catalog().tagRules(), services.tagJobs(), services.archive());
        ReplayRoutes.mount(router, http, services.engine());
        router.route("/api/*").handler(context -> Http.json(context, Http.NOT_FOUND, Map.of("code", "NOT_FOUND", "message", "No such endpoint")));

        // Built frontend from the classpath (webroot/); unknown paths fall back to index.html (client-side routing).
        // index.html is never cached, so a new build is picked up at once; the hashed assets it points to are.
        // The files are UTF-8; without this the charset is the JVM default (windows-1252 on Windows).
        router.get("/").handler(ApiServer::sendIndex);
        router.get("/index.html").handler(ApiServer::sendIndex);
        router.route().handler(StaticHandler.create("webroot").setCachingEnabled(true)
                .setDefaultContentEncoding(StandardCharsets.UTF_8.name()));
        router.get().handler(ApiServer::sendIndex);

        return vertx.createHttpServer(new HttpServerOptions().setHost(host).setPort(port)
                        .setCompressionSupported(true).setMaxWebSocketMessageSize(1 << 20))
                .webSocketHandler(socket -> {
                    if ("/ws".equals(socket.path())) services.hub().accept(socket);
                    else socket.reject();
                })
                .requestHandler(router)
                .listen();
    }

    private static void sendIndex(RoutingContext context) {
        context.response().putHeader("cache-control", "no-cache")
                .putHeader("content-type", "text/html;charset=UTF-8").sendFile("webroot/index.html")
                .onFailure(e -> context.response().setStatusCode(404).end("Frontend not built"));
    }
}
