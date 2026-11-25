package com.pcapplayer.autotag.rules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathPatternTest {

    @Test
    void plainPathMustBeEqual() {
        PathPattern pattern = new PathPattern("header.sequence");

        assertTrue(pattern.matches("header.sequence"));
        assertFalse(pattern.matches("header.sequence2"));
        assertFalse(pattern.hasWildcard());
    }

    @Test
    void wildcardMatchesAnyIndex() {
        PathPattern pattern = new PathPattern("targets[*].kind");

        assertTrue(pattern.matches("targets[0].kind"));
        assertTrue(pattern.matches("targets[12].kind"));
        assertFalse(pattern.matches("targets[].kind"));
        assertFalse(pattern.matches("targets[a].kind"));
        assertFalse(pattern.matches("targets[0].kinds"));
        assertFalse(pattern.matches("targets[0].pos.kind"));
        assertFalse(pattern.matches("other[0].kind"));
    }

    @Test
    void nestedArraysAndTrailingWildcard() {
        assertTrue(new PathPattern("a[*].b[*].c").matches("a[1].b[22].c"));
        assertFalse(new PathPattern("a[*].b[*].c").matches("a[1].b.c"));
        assertTrue(new PathPattern("samples[*]").matches("samples[2]"));
        assertFalse(new PathPattern("samples[*]").matches("samples[2].x"));
    }
}
