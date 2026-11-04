package com.str.backend.common;

import java.util.regex.Pattern;

/** Small string helpers shared across services to avoid per-class duplication. */
public final class Strings {

    private Strings() {
    }

    /** Trims the value; returns {@code null} for {@code null} or blank input. */
    public static String blankToNull(String value) {
        return (value != null && !value.isBlank()) ? value.trim() : null;
    }

    /**
     * Kontrolni znakovi (NUL, CR, LF, …) i Unicode bidi nadjačavanja (U+202A–202E, U+2066–2069). CR/LF
     * bi završili u {@code Content-Disposition} i logu, a bidi znakovi prikazuju naziv obrnuto
     * („račun<U+202E>fdp.exe" se prikazuje kao „računexe.pdf").
     */
    private static final Pattern NESIGURNI_ZNAKOVI =
            Pattern.compile("[\\p{Cntrl}\\u202A-\\u202E\\u2066-\\u2069]");

    /**
     * Naziv uploadane datoteke za pohranu: klijent može poslati i putanju u {@code filename}, pa se
     * zadržava samo naziv. Nizovnim operacijama, ne {@code Paths}: naziv poput {@code "/"} ili s NUL
     * znakom ondje baca iznimku i predaja bi pala s 500. Kontrolni i bidi znakovi se izbacuju.
     * Najviše 255 znakova (stupac {@code file_name}); prazno daje {@code fallback}.
     */
    public static String safeFileName(String originalFilename, String fallback) {
        String name = blankToNull(originalFilename);
        if (name != null) {
            name = NESIGURNI_ZNAKOVI.matcher(name).replaceAll("");
            name = blankToNull(name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1));
        }
        if (name == null) {
            return fallback;
        }
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }
}
