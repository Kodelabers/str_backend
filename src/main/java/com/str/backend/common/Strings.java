package com.str.backend.common;

/** Small string helpers shared across services to avoid per-class duplication. */
public final class Strings {

    private Strings() {
    }

    /** Trims the value; returns {@code null} for {@code null} or blank input. */
    public static String blankToNull(String value) {
        return (value != null && !value.isBlank()) ? value.trim() : null;
    }
}
