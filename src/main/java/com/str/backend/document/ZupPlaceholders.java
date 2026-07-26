package com.str.backend.document;

import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Zamjena {@code ${kljuc}} oznaka u tekstu predloška.
 *
 * <p>Ključ koji nije u kontekstu je <b>greška</b>, a ne prazan string. Prazna vrijednost je
 * legitimna (npr. iznajmljivač bez poštanskog broja) i tada mora biti izričito upisana u
 * kontekst — tako se tipfeler u predlošku razlikuje od podatka kojeg stvarno nema.
 */
public final class ZupPlaceholders {

    private static final Pattern TOKEN = Pattern.compile("\\$\\{([a-zA-Z0-9_.]+)}");

    private ZupPlaceholders() {
    }

    /**
     * @param origin datoteka i sekcija, samo za poruku o grešci
     * @throws DocumentTemplateException ako neki {@code ${...}} nema para u {@code values}
     */
    public static String bind(String text, Map<String, String> values, String origin) {
        Matcher m = TOKEN.matcher(text);
        StringBuilder out = new StringBuilder();
        TreeSet<String> unresolved = new TreeSet<>();
        while (m.find()) {
            String key = m.group(1);
            String value = values.get(key);
            if (value == null) {
                unresolved.add(key);
                value = "";
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        if (!unresolved.isEmpty()) {
            throw new DocumentTemplateException(
                    "Nepoznati placeholderi u " + origin + ": " + unresolved
                            + ". Katalog dopuštenih je u docs/ZUP-predlosci.md.");
        }
        return out.toString();
    }

    /** Koje oznake predložak koristi — za dijagnostiku i testove. */
    public static TreeSet<String> keysIn(String text) {
        TreeSet<String> keys = new TreeSet<>();
        Matcher m = TOKEN.matcher(text);
        while (m.find()) {
            keys.add(m.group(1));
        }
        return keys;
    }
}
