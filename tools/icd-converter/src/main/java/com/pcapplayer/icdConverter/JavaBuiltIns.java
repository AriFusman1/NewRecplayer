package com.pcapplayer.icdConverter;

import java.util.Set;

/** Java types that are not classes of the ICD: primitives, their boxes and String. */
final class JavaBuiltIns {

    private static final Set<String> BUILT_IN = Set.of("byte", "short", "int", "long", "float", "double", "boolean", "char",
            "Byte", "Short", "Integer", "Long", "Float", "Double", "Boolean", "Character", "String");

    private JavaBuiltIns() {
    }

    static boolean isBuiltIn(String type) {
        return BUILT_IN.contains(type);
    }
}
