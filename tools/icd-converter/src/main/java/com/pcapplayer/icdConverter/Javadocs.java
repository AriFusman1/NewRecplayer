package com.pcapplayer.icdConverter;

import com.github.javaparser.ast.nodeTypes.NodeWithJavadoc;

import java.util.Optional;

/** The text of a Javadoc comment, on one line, as a JSON {@code description}. */
final class Javadocs {

    private Javadocs() {
    }

    static Optional<String> description(NodeWithJavadoc<?> node) {
        return node.getJavadoc()
                .map(javadoc -> javadoc.getDescription().toText().replaceAll("\\s+", " ").trim())
                .filter(text -> !text.isEmpty());
    }
}
