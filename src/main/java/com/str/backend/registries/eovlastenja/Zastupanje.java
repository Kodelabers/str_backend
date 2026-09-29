package com.str.backend.registries.eovlastenja;

import java.util.List;

/**
 * Potvrđeno zakonsko zastupanje iz modula e-Zastupanja: osoba {@code personOib} je zastupnik
 * tvrtke {@code legalOib} u navedenim funkcijama. Postoji samo kad je provjera uspjela —
 * neuspjeh je {@link EOvlastenjaException}, nikad „prazan" rezultat.
 *
 * @param functions funkcije iz temeljnog registra (npr. {@code 034 Direktor}); nikad prazno
 */
public record Zastupanje(
        String personOib,
        String personFirstName,
        String personLastName,
        String legalOib,
        String legalName,
        List<Funkcija> functions
) {

    public Zastupanje {
        functions = List.copyOf(functions);
    }

    /** @param source šifra izvora (0 = sudski registar preko OIB sustava, v. šifarnik) */
    public record Funkcija(String code, String name, String source) {}
}
