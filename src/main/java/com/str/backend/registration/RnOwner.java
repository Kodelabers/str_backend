package com.str.backend.registration;

import java.util.Objects;
import java.util.UUID;

/**
 * Vlasnik RB-a za provjeru vlasništva, po modelu svakog puta prijave:
 *
 * <ul>
 *   <li>NIAS (HR/EU) → {@code oib}: efektivni OIB iz {@code EffectiveOibResolver} (tvrtka u čije
 *       ime osoba djeluje, inače osoba). {@code lessorId} nije stabilan jer svaka NIAS predaja
 *       stvara novi snapshot iznajmljivača.</li>
 *   <li>non-EU (korisničko ime i lozinka) → {@code lessorId} iz {@code LessorPrincipal}.</li>
 * </ul>
 *
 * Točno jedno od dvoje je zadano.
 */
public record RnOwner(String oib, UUID lessorId) {

    public RnOwner {
        if ((oib == null) == (lessorId == null)) {
            throw new IllegalArgumentException("Vlasnik je ili OIB ili lessorId, točno jedno");
        }
    }

    public static RnOwner byOib(String oib) {
        return new RnOwner(Objects.requireNonNull(oib, "oib"), null);
    }

    public static RnOwner byLessor(UUID lessorId) {
        return new RnOwner(null, Objects.requireNonNull(lessorId, "lessorId"));
    }
}
