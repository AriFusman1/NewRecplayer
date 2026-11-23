package com.pcapplayer.util;

/** Keeps secrets out of logs and error messages. */
public final class Secrets {

    private Secrets() {
    }

    /** "***" for any non-empty secret, "" when unset (so you can still see whether one is configured). */
    public static String mask(String secret) {
        return secret == null || secret.isEmpty() ? "" : "***";
    }
}
