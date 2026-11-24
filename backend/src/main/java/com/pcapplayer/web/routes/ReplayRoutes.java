package com.pcapplayer.web.routes;

import com.pcapplayer.common.Strings;
import com.pcapplayer.playback.ReplayEngine;
import com.pcapplayer.playback.model.ItemRequest;
import com.pcapplayer.playback.model.Target;
import com.pcapplayer.web.Http;
import io.vertx.ext.web.Router;

import java.util.List;

/** Start / stop / edit running sessions (a single replay is a one-item session). */
public final class ReplayRoutes {

    record ItemBody(String recordId, String host, int port, long sleepAfterMs) {
        ItemRequest toRequest() {
            return new ItemRequest(recordId, new Target(Strings.blankToNull(host), port), sleepAfterMs);
        }
    }

    record StartBody(List<ItemBody> items, boolean playlist, String startedBy) {
    }

    record StopBody(String by) {
    }

    record SeekBody(long positionUs, String by) {
    }

    record UpcomingBody(Long currentSleepAfterMs, List<ItemBody> items) {
    }

    private ReplayRoutes() {
    }

    private static List<ItemRequest> toRequests(List<ItemBody> items) {
        return items == null ? List.of() : items.stream().map(ItemBody::toRequest).toList();
    }

    public static void mount(Router router, Http http, ReplayEngine engine) {
        router.get("/api/sessions").handler(context -> http.blocking(context, engine::views));

        router.post("/api/sessions").handler(context -> http.blockingCreated(context, () -> {
            StartBody body = Http.body(context, StartBody.class);
            return engine.start(toRequests(body.items()), body.playlist(), body.startedBy());
        }));
        router.post("/api/sessions/:id/stop").handler(context -> http.blockingVoid(context, () ->
                engine.stop(context.pathParam("id"), Http.body(context, StopBody.class).by())));

        router.post("/api/sessions/:id/seek").handler(context -> http.blockingVoid(context, () -> {
            SeekBody body = Http.body(context, SeekBody.class);
            engine.seek(context.pathParam("id"), body.positionUs(), body.by());
        }));

        router.put("/api/sessions/:id/upcoming").handler(context -> http.blocking(context, () -> {
            UpcomingBody body = Http.body(context, UpcomingBody.class);
            return engine.updateUpcoming(context.pathParam("id"), body.currentSleepAfterMs(), toRequests(body.items()));
        }));
    }
}
