package com.pcapplayer.api;

import com.pcapplayer.catalog.CatalogRepository;
import com.pcapplayer.catalog.PcapFile;
import com.pcapplayer.catalog.Record;
import com.pcapplayer.catalog.UploadService;
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
final class UploadRoutes {

    private UploadRoutes() {
    }

    static void mount(Router r, Http http, UploadService uploads, CatalogRepository repo) {
        r.post("/api/uploads").handler(ctx -> receive(ctx, http, uploads, repo));
        r.delete("/api/uploads/:id").handler(ctx -> http.blocking(ctx, () -> {
            uploads.discard(ctx.pathParam("id"));
            return null;
        }));
    }

    private static void receive(RoutingContext ctx, Http http, UploadService uploads, CatalogRepository repo) {
        HttpServerRequest req = ctx.request();
        req.pause();
        String fileName = paramOr(ctx, "fileName", "capture.pcap");
        String uploadedBy = paramOr(ctx, "uploadedBy", "");
        Path temp = uploads.newTempPath();
        ctx.vertx().fileSystem().open(temp.toString(), new OpenOptions().setWrite(true).setCreate(true).setTruncateExisting(true))
                .compose(file -> req.pipeTo(file))
                .onSuccess(v -> http.blocking(ctx, () -> {
                    UploadService.Upload u = uploads.register(temp, fileName, uploadedBy);
                    return response(u, repo);
                }))
                .onFailure(e -> {
                    temp.toFile().delete();
                    Http.error(ctx, e);
                });
    }

    private static Map<String, Object> response(UploadService.Upload u, CatalogRepository repo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uploadId", u.id());
        m.put("fileName", u.originalName());
        m.put("size", u.size());
        m.put("sha256", u.sha256());
        m.put("summary", u.summary());
        PcapFile same = repo.fileBySha(u.sha256());
        m.put("sameFile", same == null ? null : Map.of("fileId", same.id(), "originalName", same.originalName(),
                "records", repo.records().stream().filter(x -> x.fileId().equals(same.id())).map(Record::name).toList()));
        return m;
    }

    private static String paramOr(RoutingContext ctx, String name, String def) {
        String v = ctx.queryParams().get(name);
        return v == null || v.isBlank() ? def : v;
    }

}
