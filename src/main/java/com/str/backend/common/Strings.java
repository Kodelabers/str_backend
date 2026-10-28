package com.str.backend.common;

/** Small string helpers shared across services to avoid per-class duplication. */
public final class Strings {

    private Strings() {
    }

    /** Trims the value; returns {@code null} for {@code null} or blank input. */
    public static String blankToNull(String value) {
        return (value != null && !value.isBlank()) ? value.trim() : null;
    }

    /**
     * Naziv uploadane datoteke za pohranu: klijent može poslati i putanju u {@code filename}, pa se
     * zadržava samo naziv. Nizovnim operacijama, ne {@code Paths}: naziv poput {@code "/"} ili s NUL
     * znakom ondje baca iznimku i predaja bi pala s 500. Najviše 255 znakova (stupac
     * {@code file_name}); prazno daje {@code fallback}.
     */
    public static String safeFileName(String originalFilename, String fallback) {
        String name = blankToNull(originalFilename);
        if (name != null) {
            name = name.replace("\0", "");
            name = blankToNull(name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1));
        }
        if (name == null) {
            return fallback;
        }
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }
}
