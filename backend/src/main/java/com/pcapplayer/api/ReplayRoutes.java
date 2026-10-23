package com.pcapplayer.api;

import com.pcapplayer.replay.ItemRequest;
import com.pcapplayer.replay.ReplayEngine;
import com.pcapplayer.replay.Target;
import io.vertx.ext.web.Router;

import java.util.List;

/** Start / stop / edit running sessions (a single replay is a one-item session). */
final class ReplayRoutes {

    record ItemBody(String recordId, String host, int port, long sleepAfterMs) {
        ItemRequest toRequest() {
            return new ItemRequest(recordId, new Target(host == null || host.isBlank() ? null : host.trim(), port), sleepAfterMs);
        }
    }

    record StartBody(List<ItemBody> items, boolean playlist, String startedBy) {
    }

    record StopBody(String by) {
    }

    record UpcomingBody(Long currentSleepAfterMs, List<ItemBody> items) {
    }

    private ReplayRoutes() {
    }

    static void mount(Router r, Http http, ReplayEngine engine) {
        r.get("/api/sessions").handler(ctx -> http.blocking(ctx, engine::views));
        r.post("/api/sessions").handler(ctx -> http.blocking(ctx, 201, () -> {
            StartBody b = Http.body(ctx, StartBody.class);
            List<ItemRequest> items = b.items() == null ? List.of() : b.items().stream().map(ItemBody::toRequest).toList();
            return engine.start(items, b.playlist(), b.startedBy());
        }));
        r.post("/api/sessions/:id/stop").handler(ctx -> http.blocking(ctx, () -> {
            engine.stop(ctx.pathParam("id"), Http.body(ctx, StopBody.class).by());
            return null;
        }));
        r.put("/api/sessions/:id/upcoming").handler(ctx -> http.blocking(ctx, () -> {
            UpcomingBody b = Http.body(ctx, UpcomingBody.class);
            List<ItemRequest> items = b.items() == null ? List.of() : b.items().stream().map(ItemBody::toRequest).toList();
            return engine.updateUpcoming(ctx.pathParam("id"), b.currentSleepAfterMs(), items);
        }));
    }
}
