package com.pcapplayer.icdConverter;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** Every class and enum in a folder of .java files (sub-folders included), by simple name. */
final class SourceIndex {

    private final Map<String, TypeDeclaration<?>> types = new HashMap<>();

    private SourceIndex() {
    }

    static SourceIndex of(Path folder, List<String> warnings) throws IOException {
        SourceIndex index = new SourceIndex();
        JavaParser parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));

        try (Stream<Path> files = Files.walk(folder)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                index.add(file, parser.parse(file), warnings);
            }
        }
        return index;
    }

    private void add(Path file, ParseResult<CompilationUnit> result, List<String> warnings) {
        if (result.getResult().isEmpty() || !result.isSuccessful()) {
            warnings.add(file.getFileName() + ": cannot be parsed (" + result.getProblems() + "); skipped");
            return;
        }

        for (TypeDeclaration<?> type : result.getResult().get().findAll(TypeDeclaration.class)) {
            String name = type.getNameAsString();
            if (types.putIfAbsent(name, type) != null) warnings.add(file.getFileName() + ": a second " + name + " is ignored");
        }
    }

    Optional<TypeDeclaration<?>> find(String simpleName) {
        return Optional.ofNullable(types.get(simpleName));
    }
}
