package com.pcapplayer.api.routes;

import com.pcapplayer.api.Http;
import com.pcapplayer.catalog.service.RecordService;
import com.pcapplayer.upload.UploadService;
import com.pcapplayer.util.Strings;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code POST /api/uploads?fileName=…&uploadedBy=…} with the raw file as the body. The body is
 * streamed straight to disk (no size limit, nothing held in memory), then hashed and scanned.
 */
public final class UploadRoutes {

    private UploadRoutes() {
    }

    public static void mount(Router router, Http http, UploadService uploads, RecordService records) {
        router.post("/api/uploads").handler(context -> receive(context, http, uploads, records));
        router.delete("/api/uploads/:id").handler(context -> http.blockingVoid(context, () -> uploads.discard(context.pathParam("id"))));
    }

    private static void receive(RoutingContext context, Http http, UploadService uploads, RecordService records) {
        HttpServerRequest request = context.request();
        request.pause();
        String fileName = Strings.orDefault(context.queryParams().get("fileName"), "capture.pcap");
        String uploadedBy = Strings.orDefault(context.queryParams().get("uploadedBy"), "");
        Path temp = uploads.newTempPath();
        context.vertx().fileSystem().open(temp.toString(), new OpenOptions().setWrite(true).setCreate(true).setTruncateExisting(true))
                .compose(file -> request.pipeTo(file))
                .onSuccess(ignored -> http.blocking(context, () -> {
                    UploadService.Upload upload = uploads.register(temp, fileName, uploadedBy);
                    return response(upload, records);
                }))
                .onFailure(e -> {
                    temp.toFile().delete();
                    Http.error(context, e);
                });
    }

    private static Map<String, Object> response(UploadService.Upload upload, RecordService records) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("uploadId", upload.id());
        body.put("fileName", upload.originalName());
        body.put("size", upload.size());
        body.put("sha256", upload.sha256());
        body.put("summary", upload.summary());
        body.put("sameFile", records.sameFile(upload.sha256()));
        return body;
    }
}
