package com.pcapplayer.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {

    private static final String S3_FILE = """
            storage:
              type: s3
            s3:
              endpoint: "http://minio.internal:9000"
              bucket: from-file
              access-key: file-access
              secret-key: file-secret
              path-style: true
            """;

    private static Path writeDefaultFile(Path dir, String yaml) throws IOException {
        Path file = dir.resolve(ConfigLoader.DEFAULT_CONFIG_FILE);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, yaml);
    }

    @Test
    void readsS3SettingsFromConfigApplicationYmlAutomatically(@TempDir Path dir) throws IOException {
        writeDefaultFile(dir, S3_FILE);
        AppConfig cfg = ConfigLoader.load(Map.of(), dir);
        assertEquals("s3", cfg.storage().type());
        assertEquals("http://minio.internal:9000", cfg.s3().endpoint());
        assertEquals("from-file", cfg.s3().bucket());
        assertEquals("file-access", cfg.s3().accessKey());
        assertEquals("file-secret", cfg.s3().secretKey());
        assertTrue(cfg.s3().pathStyle());
        assertEquals("us-east-1", cfg.s3().region(), "keys left out of the file keep their defaults");
        assertEquals(8080, cfg.server().port());
    }

    @Test
    void withoutAFileTheDefaultsApply(@TempDir Path dir) throws IOException {
        AppConfig cfg = ConfigLoader.load(Map.of(), dir);
        assertEquals("", cfg.s3().endpoint());
        assertEquals("pcap-player", cfg.s3().bucket());
    }

    @Test
    void environmentVariablesOverrideTheFile(@TempDir Path dir) throws IOException {
        writeDefaultFile(dir, S3_FILE);
        AppConfig cfg = ConfigLoader.load(Map.of("S3_SECRET_KEY", "env-secret", "SERVER_PORT", "9090"), dir);
        assertEquals("env-secret", cfg.s3().secretKey());
        assertEquals("file-access", cfg.s3().accessKey());
        assertEquals(9090, cfg.server().port());
    }

    @Test
    void configFileVariableChoosesAnotherFile(@TempDir Path dir) throws IOException {
        writeDefaultFile(dir, S3_FILE);
        Files.writeString(dir.resolve("other.yml"), "s3:\n  bucket: from-other\n");
        AppConfig cfg = ConfigLoader.load(Map.of("CONFIG_FILE", "other.yml"), dir);
        assertEquals("from-other", cfg.s3().bucket());
        assertEquals("", cfg.s3().accessKey(), "only the named file is used, not config/application.yml");
    }

    @Test
    void aMissingConfigFileIsAnErrorNotSilentlyIgnored(@TempDir Path dir) {
        IOException e = assertThrows(IOException.class, () -> ConfigLoader.load(Map.of("CONFIG_FILE", "nope.yml"), dir));
        assertTrue(e.getMessage().contains("nope.yml"));
    }

    @Test
    void anEmptyConfigFileMeansDefaults(@TempDir Path dir) throws IOException {
        writeDefaultFile(dir, "# nothing set yet\n");
        AppConfig cfg = ConfigLoader.load(Map.of(), dir);
        assertEquals("pcap-player", cfg.s3().bucket());
    }

    @Test
    void theSecretKeyIsNeverPrinted(@TempDir Path dir) throws IOException {
        writeDefaultFile(dir, S3_FILE);
        AppConfig cfg = ConfigLoader.load(Map.of(), dir);
        assertFalse(cfg.toString().contains("file-secret"));
        assertTrue(cfg.s3().toString().contains("secretKey=***"));
    }
}
