package com.pcapplayer.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;

/**
 * Loads {@code application.yml} from the classpath, merges an optional {@code CONFIG_FILE}, then
 * applies environment overrides: {@code s3.access-key} ← {@code S3_ACCESS_KEY}.
 */
public final class ConfigLoader {

    private ConfigLoader() {
    }

    public static AppConfig load(Map<String, String> env) throws IOException {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        ObjectNode tree;
        try (InputStream in = ConfigLoader.class.getResourceAsStream("/application.yml")) {
            if (in == null) throw new IOException("application.yml missing from classpath");
            tree = (ObjectNode) yaml.readTree(in);
        }
        String extra = env.get("CONFIG_FILE");
        if (extra != null && !extra.isBlank()) {
            merge(tree, yaml.readTree(Files.readAllBytes(Path.of(extra))));
        }
        applyEnv(tree, "", env);
        return new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .treeToValue(tree, AppConfig.class);
    }

    private static void merge(ObjectNode into, JsonNode from) {
        if (from == null || !from.isObject()) return;
        Iterator<Map.Entry<String, JsonNode>> it = from.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode cur = into.get(e.getKey());
            if (cur instanceof ObjectNode o && e.getValue().isObject()) merge(o, e.getValue());
            else into.set(e.getKey(), e.getValue());
        }
    }

    private static void applyEnv(ObjectNode node, String path, Map<String, String> env) {
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String p = path.isEmpty() ? e.getKey() : path + "." + e.getKey();
            if (e.getValue() instanceof ObjectNode child) {
                applyEnv(child, p, env);
                continue;
            }
            String v = env.get(p.toUpperCase().replace('.', '_').replace('-', '_'));
            if (v != null) node.put(e.getKey(), v); // Jackson coerces "8080" / "true" to the record's types
        }
    }
}
