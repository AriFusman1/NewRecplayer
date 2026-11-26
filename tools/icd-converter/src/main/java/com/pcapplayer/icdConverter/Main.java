package com.pcapplayer.icdConverter;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Command line: {@code java -jar icd-converter.jar SOURCES_FOLDER CONFIG.json [OUTPUT.icd.json]}.
 * Writes the ICD JSON (to the output file, or to the screen), and the warnings to stderr.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2 || args.length > 3) {
            System.err.println("usage: java -jar icd-converter.jar SOURCES_FOLDER CONFIG.json [OUTPUT.icd.json]");
            System.exit(2);
        }
        JsonNode config = IcdConverter.MAPPER.readTree(Path.of(args[1]).toFile());
        IcdConverter.Result result = IcdConverter.convert(Path.of(args[0]), config);
        String json = IcdConverter.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result.icd()) + System.lineSeparator();

        if (args.length == 3) {
            Files.writeString(Path.of(args[2]), json, StandardCharsets.UTF_8);
            System.err.println("wrote " + args[2]);
        } else {
            System.out.print(json);
        }
        result.warnings().forEach(warning -> System.err.println("warning: " + warning));
    }
}
