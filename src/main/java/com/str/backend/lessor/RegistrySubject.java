package com.str.backend.lessor;

/**
 * Fizička osoba kako je vraća registar ({@link SubjectRegistry}), u našem obliku — neovisno o
 * tome je li izvor OIB sustav ili {@code str.subject*}.
 *
 * <p>Kontakta nema: nema ga ni jedan od dvaju izvora (OIB sustav ga u shemi ne vodi, eTurizam
 * {@code str.subject} također ne), pa ga korisnik upisuje na formi.
 *
 * @param municipality grad/općina prebivališta; služi da se izvede {@code county} kad ga izvor
 *                     ne daje (OIB sustav)
 * @param municipalityCode šifra grada/općine (OIB sustav {@code sifraOpcine}, isto što i
 *                         {@code rpj_dgu.gradovi_i_opcine.jls_mb}); po njoj se županija izvodi
 *                         egzaktno, bez usporedbe naziva. {@code null} kad je izvor ne daje
 * @param county       županija prebivališta, u obliku {@code rpj_dgu.zupanije.zu_ime} — tim se
 *                     oblikom uspoređuje sa županijom objekta u GO-1; {@code null} kad je izvor ne zna
 * @param legalEntityName naziv subjekta kad ga registar vodi ({@code str.subject_version.name});
 *                        OIB sustav za fizičku osobu ga nema
 * @param source       koji ju je registar vratio
 */
public record RegistrySubject(
        String oib,
        String firstName,
        String lastName,
        String legalEntityName,
        String street,
        String streetNumber,
        String place,
        String postalCode,
        String municipality,
        String municipalityCode,
        String county,
        SubjectDataSource source
) {

    /** Izvor bez šifre općine ({@code str.subject*}, testovi). */
    public RegistrySubject(String oib, String firstName, String lastName, String legalEntityName, String street,
                           String streetNumber, String place, String postalCode, String municipality,
                           String county, SubjectDataSource source) {
        this(oib, firstName, lastName, legalEntityName, street, streetNumber, place, postalCode, municipality,
                null, county, source);
    }
}
