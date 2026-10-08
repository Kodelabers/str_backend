package com.str.backend.auth.nias;

import com.str.backend.lessor.LegalEntityProfile;
import com.str.backend.lessor.SubjectDataSource;
import com.str.backend.lessor.SubjectProfile;
import com.str.backend.str.StrSubjectRepository.DocumentContactRow;

/**
 * Podaci o podnositelju za prikaz na formi zahtjeva za RB (stavka 2: sva polja vidljiva, ništa
 * implicitno). Svaka grupa nosi izvor, da frontend može označiti odakle je podatak.
 *
 * <p>Sve je samo za prikaz — pri izdavanju RB-a backend iste podatke dohvaća sam, pa izmjena na
 * klijentu nema učinka. <b>Kontakt</b> ({@code kontaktMobitel}, {@code kontaktTelefon},
 * {@code kontaktOsoba}, {@code kontaktEmail}) dolazi iz {@code str.document_contact} ako postoji —
 * u ime tvrtke s dokumenata tvrtke. Korisnik ga smije izmijeniti na formi; {@code null} ako se ne
 * pronađe.
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
         * Pravna osoba u čije ime korisnik djeluje (e-Zastupanja), s MBS-om i sjedištem;
         * {@code null} kad djeluje u svoje ime. Kad je postavljena, podaci iznad su podaci o
         * <b>jednom zakonskom zastupniku</b> tvrtke: koji je to zastupnik zna eTurizam (inače je to
         * NIAS osoba), a ime i adresa su iz OIB sustava. Adresa se šalje samo kad je zastupnik NIAS
         * osoba; {@code adresaIzvor} je {@code null} kad adrese nema.
         */
        ActingSubjectResponse pravnaOsoba,
        /** Iz {@code str.document_contact.mobile}; {@code null} ako nije pronađen. */
        String kontaktMobitel,
        /** Iz {@code str.document_contact.phone}; {@code null} ako nije pronađen. */
        String kontaktTelefon,
        /** Iz {@code str.document_contact.name}; {@code null} ako nije pronađen. */
        String kontaktOsoba,
        /** Iz {@code str.document_contact.email}; {@code null} ako nije pronađen. */
        String kontaktEmail
) {

    /** Korisnik djeluje u svoje ime: podaci o njemu iz NIAS-a i registra. */
    static SubjectProfileResponse of(SubjectProfile p, DocumentContactRow contact) {
        return new SubjectProfileResponse(
                p.oib(), p.firstName(), p.lastName(), p.nameSource(), p.legalEntityName(),
                p.street(), p.streetNumber(), p.place(), p.postalCode(), p.municipality(),
                p.county(), p.addressSource(), null,
                contact == null ? null : contact.getMobile(),
                contact == null ? null : contact.getPhone(),
                contact == null ? null : contact.getName(),
                contact == null ? null : contact.getEmail());
    }

    /** Korisnik djeluje u ime tvrtke: jedan zastupnik i tvrtka s MBS-om i sjedištem. */
    static SubjectProfileResponse ofLegalEntity(ActingSubject s, LegalEntityProfile p, DocumentContactRow contact) {
        SubjectProfile r = p.representative();
        return new SubjectProfileResponse(
                r.oib(), r.firstName(), r.lastName(), r.nameSource(), null,
                r.street(), r.streetNumber(), r.place(), r.postalCode(), r.municipality(),
                r.county(), r.addressSource(), ActingSubjectResponse.of(s, p),
                contact == null ? null : contact.getMobile(),
                contact == null ? null : contact.getPhone(),
                contact == null ? null : contact.getName(),
                contact == null ? null : contact.getEmail());
    }
}
