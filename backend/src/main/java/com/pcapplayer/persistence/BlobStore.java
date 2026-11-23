package com.pcapplayer.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

/** Key/value object storage (S3 today). Keys look like {@code records/abc.json}. */
public interface BlobStore {

    void put(String key, byte[] data, String contentType) throws IOException;

    /** Uploads a (possibly large) local file. */
    void putFile(String key, Path file, String contentType) throws IOException;

    /** @return the object's bytes, or null if it does not exist */
    byte[] get(String key) throws IOException;

    /** Streams the object; caller closes. @throws java.io.FileNotFoundException if missing */
    InputStream open(String key) throws IOException;

    void download(String key, Path target) throws IOException;

    void delete(String key) throws IOException;

    /** All keys under the prefix. */
    List<String> list(String prefix) throws IOException;

    /** Cheap reachability check for the readiness probe. */
    void ping() throws IOException;
}
