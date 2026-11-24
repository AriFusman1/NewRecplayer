package com.pcapplayer.configuration;

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
import java.util.logging.Logger;

/**
 * Builds the configuration in three layers, each overriding the one before:
 * <ol>
 *   <li>defaults: {@code application.yml} on the classpath</li>
 *   <li>your configuration file, the place for the S3 address and credentials: the file named by
 *       {@code CONFIG_FILE}, otherwise {@code config/application.yml} under the working directory
 *       if it exists (in the Docker image: {@code /app/config/application.yml})</li>
 *   <li>environment variables: {@code s3.access-key} ← {@code S3_ACCESS_KEY}</li>
 * </ol>
 */
public class ConfigLoader {

    private static final Logger LOG = Logger.getLogger(ConfigLoader.class.getName());

    /** Looked up under the working directory when {@code CONFIG_FILE} is not set. */
    static final Path DEFAULT_CONFIG_FILE = Path.of("config", "application.yml");

    private ConfigLoader() {
    }

    public static AppConfig load(Map<String, String> env) throws IOException {
        return load(env, Path.of("").toAbsolutePath());
    }

    static AppConfig load(Map<String, String> env, Path workingDir) throws IOException {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        ObjectNode tree;

        try (InputStream in = ConfigLoader.class.getResourceAsStream("/application.yml")) {
            if (in == null) throw new IOException("application.yml missing from classpath");
            tree = (ObjectNode) yaml.readTree(in);
        }
        Path file = configFile(env, workingDir);

        if (file != null) {
            JsonNode overrides = yaml.readTree(Files.readAllBytes(file)); // empty or comments-only: nothing set
            // Jackson merges nested sections key by key: keys left out of the file keep their defaults
            if (overrides != null && overrides.isObject()) yaml.readerForUpdating(tree).readValue(overrides);
            LOG.info("Configuration file: " + file);
        }
        applyEnv(tree, "", env);
        return new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .treeToValue(tree, AppConfig.class);
    }

    /**
     * The file named by {@code CONFIG_FILE} (which must exist), else the default file if present,
     * else null (defaults and environment only).
     */
    static Path configFile(Map<String, String> env, Path workingDir) throws IOException {
        String named = env.get("CONFIG_FILE");

        if (named != null && !named.isBlank()) {
            Path file = workingDir.resolve(named.trim());
            if (!Files.isRegularFile(file)) throw new IOException("CONFIG_FILE not found: " + file);
            return file;
        }
        Path file = workingDir.resolve(DEFAULT_CONFIG_FILE);
        return Files.isRegularFile(file) ? file : null;
    }

    private static void applyEnv(ObjectNode node, String path, Map<String, String> env) {
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();

        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String keyPath = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();

            if (entry.getValue() instanceof ObjectNode child) {
                applyEnv(child, keyPath, env);
                continue;
            }
            String envValue = env.get(keyPath.toUpperCase().replace('.', '_').replace('-', '_'));
            if (envValue != null) node.put(entry.getKey(), envValue); // Jackson coerces "8080" / "true" to the record's types
        }
    }
}
