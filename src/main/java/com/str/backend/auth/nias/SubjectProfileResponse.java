package com.str.backend.auth.nias;

import com.str.backend.lessor.SubjectDataSource;
import com.str.backend.lessor.SubjectProfile;

/**
 * Podaci o podnositelju za prikaz na formi zahtjeva za RB (stavka 2: sva polja vidljiva, ništa
 * implicitno). Svaka grupa nosi izvor, da frontend može označiti odakle je podatak.
 *
 * <p>Sve je samo za prikaz — pri izdavanju RB-a backend iste podatke dohvaća sam, pa izmjena na
 * klijentu nema učinka. <b>Kontakta nema</b>: ne vodi ga ni OIB sustav ni {@code str.subject},
 * pa ga korisnik upisuje u {@code kontakt*} polja zahtjeva.
 *
 * <p>{@code adresaIzvor} je {@code STR_SUBJEKT} dok OIB sustav nije uključen, a
 * {@code OIB_REGISTAR} nakon toga.
 */
public record SubjectProfileResponse(
        String oib,
        String ime,
        String prezime,
        SubjectDataSource imeIzvor,
        String nazivSubjekta,
        String ulica,
        String kucniBroj,
        String mjesto,
        String postanskiBroj,
        String opcina,
        String zupanija,
        SubjectDataSource adresaIzvor
) {

    static SubjectProfileResponse of(SubjectProfile p) {
        return new SubjectProfileResponse(
                p.oib(), p.firstName(), p.lastName(), p.nameSource(), p.legalEntityName(),
                p.street(), p.streetNumber(), p.place(), p.postalCode(), p.municipality(),
                p.county(), p.addressSource());
    }
}
