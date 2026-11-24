package com.pcapplayer.web.routes;

import com.pcapplayer.persistence.BlobStore;
import com.pcapplayer.web.Http;
import io.vertx.ext.web.Router;

import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * OpenShift probes. Liveness: the process answers. Readiness: metadata is loaded and storage reachable.
 */
public final class HealthRoutes {

    private HealthRoutes() {
    }

    public static void mount(Router router, Http http, BlobStore store, BooleanSupplier loaded) {
        router.get("/health/live").handler(context -> Http.json(context, Http.OK, Map.of("status", "UP")));

        router.get("/health/ready").handler(context -> context.vertx().executeBlocking(() -> {
            if (!loaded.getAsBoolean()) return "metadata not loaded";
            store.ping();
            return null;
        }, false).onComplete(result -> {
            if (result.succeeded() && result.result() == null) Http.json(context, Http.OK, Map.of("status", "UP"));
            else Http.json(context, Http.SERVICE_UNAVAILABLE, Map.of("status", "DOWN", "reason",
                    result.succeeded() ? result.result() : String.valueOf(result.cause().getMessage())));
        }));
    }
}
