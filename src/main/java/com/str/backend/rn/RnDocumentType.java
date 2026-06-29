package com.str.backend.rn;

import java.util.Optional;

/** STR-2.1: akti koje voditelj postupka generira u suspenzijskom/povlačenjskom toku. */
public enum RnDocumentType {

    DOPIS_NAMJERE("dopis-namjere", "DOPIS O NAMJERI SUSPENZIJE REGISTRACIJSKOG BROJA"),
    NALOG_SUSPENZIJA("nalog-suspenzija", "NALOG ZA SUSPENZIJU REGISTRACIJSKOG BROJA"),
    NALOG_POVLACENJE("nalog-povlacenje", "NALOG ZA POVLAČENJE (BRISANJE) REGISTRACIJSKOG BROJA");

    private final String slug;
    private final String title;

    RnDocumentType(String slug, String title) {
        this.slug = slug;
        this.title = title;
    }

    public String slug() {
        return slug;
    }

    public String title() {
        return title;
    }

    public static Optional<RnDocumentType> fromSlug(String slug) {
        for (RnDocumentType t : values()) {
            if (t.slug.equals(slug)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }
}
