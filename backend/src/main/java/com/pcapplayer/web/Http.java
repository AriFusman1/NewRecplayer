package com.pcapplayer.web;

import com.pcapplayer.common.Json;
import com.pcapplayer.errorHandling.ApiException;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.Vertx;
import io.vertx.ext.web.RoutingContext;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Small helpers shared by the route classes: run blocking work, read JSON bodies, write JSON / errors. */
public class Http {

    public static final int OK = HttpResponseStatus.OK.code();
    public static final int CREATED = HttpResponseStatus.CREATED.code();
    public static final int NO_CONTENT = HttpResponseStatus.NO_CONTENT.code();
    public static final int NOT_FOUND = HttpResponseStatus.NOT_FOUND.code();
    public static final int SERVICE_UNAVAILABLE = HttpResponseStatus.SERVICE_UNAVAILABLE.code();
    private static final int INTERNAL_SERVER_ERROR = HttpResponseStatus.INTERNAL_SERVER_ERROR.code();

    private static final Logger LOG = Logger.getLogger(Http.class.getName());

    private final Vertx vertx;

    Http(Vertx vertx) {
        this.vertx = vertx;
    }

    /** Runs {@code work} on the worker pool and answers with its result as JSON (204 if null). */
    public <T> void blocking(RoutingContext context, Callable<T> work) {
        blocking(context, OK, work);
    }

    /** Like {@link #blocking(RoutingContext, Callable)}, answering 201 Created. */
    public <T> void blockingCreated(RoutingContext context, Callable<T> work) {
        blocking(context, CREATED, work);
    }

    private <T> void blocking(RoutingContext context, int status, Callable<T> work) {
        vertx.<T>executeBlocking(work, false).onComplete(result -> {
            if (result.succeeded()) json(context, result.result() == null ? NO_CONTENT : status, result.result());
            else error(context, result.cause());
        });
    }

    public static <T> T body(RoutingContext context, Class<T> type) {
        if (context.body() == null || context.body().buffer() == null || context.body().length() == 0) {
            throw ApiException.badRequest("BODY_REQUIRED", "Request body is required");
        }

        try {
            return Json.MAPPER.readValue(context.body().buffer().getBytes(), type);
        } catch (IOException e) {
            throw ApiException.badRequest("BAD_JSON", "Malformed JSON: " + e.getMessage());
        }
    }

    public static void json(RoutingContext context, int status, Object value) {
        if (context.response().ended()) return;
        context.response().setStatusCode(status);

        if (status == NO_CONTENT) {
            context.response().end();
            return;
        }
        context.response().putHeader("content-type", "application/json; charset=utf-8").end(Json.string(value));
    }

    /** Runs {@code work} on the worker pool and answers 204 No Content. */
    public void blockingVoid(RoutingContext context, ThrowingRunnable work) {
        blocking(context, () -> {
            work.run();
            return null;
        });
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** {@link ApiException}s (including replay failures) become {code, message, params}; anything else is a 500. */
    public static void error(RoutingContext context, Throwable failure) {
        if (failure instanceof ApiException e) {
            json(context, e.status(), errorBody(e.code(), e.getMessage(), e.params()));
            return;
        }
        LOG.log(Level.SEVERE, "Unhandled error on " + context.request().method() + " " + context.request().path(), failure);
        json(context, INTERNAL_SERVER_ERROR, errorBody("INTERNAL_ERROR", String.valueOf(failure.getMessage()), Map.of()));
    }

    private static Map<String, Object> errorBody(String code, String message, Map<String, Object> params) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("params", params);
        return body;
    }
}
