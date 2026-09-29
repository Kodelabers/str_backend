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
        SubjectDataSource adresaIzvor,
        /**
         * Pravna osoba u čije ime korisnik djeluje (e-Zastupanja); {@code null} kad djeluje u svoje
         * ime. Kad je postavljena, podaci iznad su podaci o <b>zastupniku</b>: OIB i ime, bez
         * adrese — iznajmljivač je tvrtka, pa se prebivalište zastupnika ne dohvaća.
         */
        ActingSubjectResponse pravnaOsoba
) {

    /** Korisnik djeluje u svoje ime: podaci o njemu iz NIAS-a i registra. */
    static SubjectProfileResponse of(SubjectProfile p) {
        return new SubjectProfileResponse(
                p.oib(), p.firstName(), p.lastName(), p.nameSource(), p.legalEntityName(),
                p.street(), p.streetNumber(), p.place(), p.postalCode(), p.municipality(),
                p.county(), p.addressSource(), null);
    }

    /** Korisnik djeluje u ime tvrtke: zastupnik bez adrese i tvrtka iz e-Ovlaštenja. */
    static SubjectProfileResponse ofRepresentative(ActingSubject s) {
        return new SubjectProfileResponse(
                s.representativeOib(), s.representativeFirstName(), s.representativeLastName(),
                SubjectDataSource.NIAS, null,
                null, null, null, null, null, null, null,
                ActingSubjectResponse.of(s));
    }
}
