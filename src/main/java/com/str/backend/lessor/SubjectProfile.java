package com.str.backend.lessor;

/**
 * Podaci o fizičkoj osobi koja traži RB, iz kojih se gradi {@link LessorEntity} i koje korisnik
 * vidi na formi (stavka 2: „sva polja moraju biti vidljiva, ništa implicitno slati").
 *
 * <p>OIB je iz sesije, ime i prezime iz NIAS-a (iz registra samo kad ih NIAS nema — local/mock),
 * adresa iz registra ({@link SubjectRegistry}). Kontakta nema jer ga ne vodi nijedan izvor —
 * korisnik ga upisuje na formi.
 *
 * @param nameSource    odakle su ime i prezime stvarno došli
 * @param addressSource koji je registar dao adresu
 */
public record SubjectProfile(
        String oib,
        String firstName,
        String lastName,
        SubjectDataSource nameSource,
        String legalEntityName,
        String street,
        String streetNumber,
        String place,
        String postalCode,
        String municipality,
        String county,
        SubjectDataSource addressSource
) {
}
