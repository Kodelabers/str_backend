package com.str.backend.lessor;

/**
 * Pravna osoba kako je vraća OIB sustav ({@link LegalEntityRegistry}), u našem obliku.
 *
 * <p>Uredba (EU) 2024/1028, čl. 5(1)(c) za pravnu osobu traži naziv, nacionalni registracijski
 * broj i adresu sjedišta — to su polja ovog zapisa. Kontakta nema: OIB sustav ga ne vodi.
 *
 * @param registrationNumber MBS (matični broj subjekta u sudskom registru); {@code null} kad ga
 *                           registar nema — testna tvrtka ga, primjerice, nema
 * @param municipality       grad/općina sjedišta; iz nje se izvodi županija kad šifre nema
 * @param municipalityCode   šifra grada/općine (OIB sustav {@code sifraOpcine}, isto što i
 *                           {@code rpj_dgu.gradovi_i_opcine.jls_mb}); po njoj se županija izvodi
 *                           egzaktno. {@code null} kad je izvor ne daje
 * @param county             županija sjedišta; OIB sustav je ne vraća, pa je {@code null} dok je
 *                           ne izvede {@link SubjectProfileService}
 */
public record RegistryLegalEntity(
        String oib,
        String name,
        String registrationNumber,
        String street,
        String streetNumber,
        String place,
        String postalCode,
        String municipality,
        String municipalityCode,
        String county,
        SubjectDataSource source
) {

    /** Izvor bez šifre općine (testovi). */
    public RegistryLegalEntity(String oib, String name, String registrationNumber, String street,
                               String streetNumber, String place, String postalCode, String municipality,
                               String county, SubjectDataSource source) {
        this(oib, name, registrationNumber, street, streetNumber, place, postalCode, municipality, null, county,
                source);
    }
}
