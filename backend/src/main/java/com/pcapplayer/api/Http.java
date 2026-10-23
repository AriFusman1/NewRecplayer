package com.pcapplayer.api;

import com.pcapplayer.replay.ReplayFailure;
import com.pcapplayer.util.ApiException;
import com.pcapplayer.util.Json;
import io.vertx.core.Vertx;
import io.vertx.ext.web.RoutingContext;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Small helpers shared by the route classes: run blocking work, read JSON bodies, write JSON / errors. */
final class Http {

    private static final Logger LOG = Logger.getLogger(Http.class.getName());
    private static final Set<String> CONFLICT_CODES = Set.of("TARGET_BUSY", "LISTEN_FAILED");
    private static final Set<String> NOT_FOUND_CODES = Set.of("SESSION_NOT_FOUND", "RECORD_NOT_FOUND");

    private final Vertx vertx;

    Http(Vertx vertx) {
        this.vertx = vertx;
    }

    /** Runs {@code work} on the worker pool and answers with its result as JSON (204 if null). */
    <T> void blocking(RoutingContext ctx, Callable<T> work) {
        blocking(ctx, 200, work);
    }

    <T> void blocking(RoutingContext ctx, int status, Callable<T> work) {
        vertx.<T>executeBlocking(work, false).onComplete(ar -> {
            if (ar.succeeded()) json(ctx, ar.result() == null ? 204 : status, ar.result());
            else error(ctx, ar.cause());
        });
    }

    static <T> T body(RoutingContext ctx, Class<T> type) {
        if (ctx.body() == null || ctx.body().buffer() == null || ctx.body().length() == 0) {
            throw ApiException.bad("BODY_REQUIRED", "Request body is required");
        }
        try {
            return Json.MAPPER.readValue(ctx.body().buffer().getBytes(), type);
        } catch (IOException e) {
            throw ApiException.bad("BAD_JSON", "Malformed JSON: " + e.getMessage());
        }
    }

    static void json(RoutingContext ctx, int status, Object value) {
        if (ctx.response().ended()) return;
        ctx.response().setStatusCode(status);
        if (status == 204) {
            ctx.response().end();
            return;
        }
        ctx.response().putHeader("content-type", "application/json; charset=utf-8").end(Json.string(value));
    }

    static void error(RoutingContext ctx, Throwable t) {
        int status;
        Map<String, Object> body = new LinkedHashMap<>();
        if (t instanceof ApiException e) {
            status = e.status();
            body.put("code", e.code());
            body.put("message", e.getMessage());
            body.put("params", e.params());
        } else if (t instanceof ReplayFailure f) {
            status = CONFLICT_CODES.contains(f.code()) ? 409 : NOT_FOUND_CODES.contains(f.code()) ? 404 : 422;
            body.put("code", f.code());
            body.put("message", f.getMessage());
            body.put("params", f.params());
        } else {
            LOG.log(Level.SEVERE, "Unhandled error on " + ctx.request().method() + " " + ctx.request().path(), t);
            status = 500;
            body.put("code", "INTERNAL_ERROR");
            body.put("message", String.valueOf(t.getMessage()));
            body.put("params", Map.of());
        }
        json(ctx, status, body);
    }
}
