package com.str.backend.auth.nias;

import com.str.backend.lessor.LegalEntityProfile;
import com.str.backend.lessor.SubjectDataSource;

import java.time.Instant;
import java.util.List;

/**
 * Pravna osoba u čije ime korisnik djeluje, s podacima o zastupniku, potvrđena kroz e-Ovlaštenja.
 *
 * <p>MBS i sjedište se pune samo u {@code GET /api/nias/subject} ({@link #of(ActingSubject,
 * LegalEntityProfile)}); odabir tvrtke ({@code /acting-subject}) ih ne dohvaća, pa su ondje
 * {@code null}.
 *
 * @param funkcije   funkcije NIAS osobe iz e-Zastupanja (npr. „Direktor")
 * @param izvor      izvor OIB-a, naziva i funkcija — uvijek {@code E_OVLASTENJA}
 * @param mbs        matični broj subjekta (Uredba 2024/1028, čl. 5(1)(c)(ii)); {@code null} kad ga
 *                   OIB sustav nema
 * @param adresaIzvor izvor MBS-a i sjedišta; {@code null} kad nisu dohvaćeni
 */
public record ActingSubjectResponse(
        String oib,
        String naziv,
        List<String> funkcije,
        String zastupnikOib,
        String zastupnikIme,
        String zastupnikPrezime,
        Instant provjereno,
        String izvor,
        String mbs,
        String ulica,
        String kucniBroj,
        String mjesto,
        String postanskiBroj,
        String opcina,
        String zupanija,
        SubjectDataSource adresaIzvor
) {

    static ActingSubjectResponse of(ActingSubject s) {
        return new ActingSubjectResponse(s.legalOib(), s.legalName(), s.functions(),
                s.representativeOib(), s.representativeFirstName(), s.representativeLastName(),
                s.verifiedAt(), "E_OVLASTENJA",
                null, null, null, null, null, null, null, null);
    }

    static ActingSubjectResponse of(ActingSubject s, LegalEntityProfile p) {
        return new ActingSubjectResponse(s.legalOib(), s.legalName(), s.functions(),
                s.representativeOib(), s.representativeFirstName(), s.representativeLastName(),
                s.verifiedAt(), "E_OVLASTENJA",
                p.registrationNumber(), p.street(), p.streetNumber(), p.place(), p.postalCode(),
                p.municipality(), p.county(), p.seatSource());
    }
}
