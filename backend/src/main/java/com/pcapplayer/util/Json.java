package com.pcapplayer.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;

/** The one shared Jackson mapper (records ↔ JSON, camelCase, unknown fields ignored). */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setSerializationInclusion(JsonInclude.Include.ALWAYS);

    private Json() {
    }

    public static byte[] bytes(Object o) {
        try {
            return MAPPER.writeValueAsBytes(o);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String string(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static <T> T read(byte[] data, Class<T> type) {
        try {
            return MAPPER.readValue(data, type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
