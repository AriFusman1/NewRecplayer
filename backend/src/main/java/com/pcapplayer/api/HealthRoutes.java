package com.pcapplayer.api;

import com.pcapplayer.storage.BlobStore;
import io.vertx.ext.web.Router;

import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * OpenShift probes. Liveness: the process answers. Readiness: metadata is loaded and storage reachable.
 */
final class HealthRoutes {

    private HealthRoutes() {
    }

    static void mount(Router r, Http http, BlobStore store, BooleanSupplier loaded) {
        r.get("/health/live").handler(ctx -> Http.json(ctx, 200, Map.of("status", "UP")));
        r.get("/health/ready").handler(ctx -> ctx.vertx().executeBlocking(() -> {
            if (!loaded.getAsBoolean()) return "metadata not loaded";
            store.ping();
            return null;
        }, false).onComplete(ar -> {
            if (ar.succeeded() && ar.result() == null) Http.json(ctx, 200, Map.of("status", "UP"));
            else Http.json(ctx, 503, Map.of("status", "DOWN", "reason",
                    ar.succeeded() ? ar.result() : String.valueOf(ar.cause().getMessage())));
        }));
    }
}
