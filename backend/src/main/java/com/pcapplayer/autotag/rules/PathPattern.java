package com.pcapplayer.autotag.rules;

/**
 * A rule path matched against the paths the decoder produces.
 * <p>
 * The ICD tree writes array elements as {@code [*]} ({@code targets[*].kind}); a decoded message has
 * one entry per element ({@code targets[0].kind}, {@code targets[1].kind}, …). {@code [*]} matches
 * any {@code [<digits>]}, so nested arrays ({@code a[*].b[*].c}) work too. A path without {@code [*]}
 * must be equal.
 */
public final class PathPattern {

    private static final String WILDCARD = "[*]";

    private final String pattern;
    private final boolean wildcard;

    /** The text before the first {@code [*]}: a cheap first check before the full match. */
    private final String fixedPrefix;

    public PathPattern(String pattern) {
        this.pattern = pattern;
        int star = pattern.indexOf(WILDCARD);
        this.wildcard = star >= 0;
        this.fixedPrefix = wildcard ? pattern.substring(0, star) : pattern;
    }

    public String pattern() {
        return pattern;
    }

    /** True if the path goes through an array: it can match several decoded values. */
    public boolean hasWildcard() {
        return wildcard;
    }

    public boolean matches(String path) {
        if (!wildcard) return pattern.equals(path);
        if (!path.startsWith(fixedPrefix)) return false;

        int p = 0;
        int i = 0;

        while (p < pattern.length()) {
            if (pattern.startsWith(WILDCARD, p)) {
                // [*] matches "[" digits "]"
                if (i >= path.length() || path.charAt(i) != '[') return false;
                int digitsStart = ++i;
                while (i < path.length() && Character.isDigit(path.charAt(i))) i++;
                if (i == digitsStart || i >= path.length() || path.charAt(i) != ']') return false;
                i++;
                p += WILDCARD.length();
            } else {
                if (i >= path.length() || path.charAt(i) != pattern.charAt(p)) return false;
                i++;
                p++;
            }
        }
        return i == path.length();
    }
}
