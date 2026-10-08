package com.str.backend.lessor;

/**
 * Tvrtka u čije ime NIAS osoba traži RB, s jednim zakonskim zastupnikom — podaci koje traži
 * Uredba (EU) 2024/1028, čl. 5(1)(c). Iz njih se gradi {@link LessorEntity} i njih korisnik vidi
 * na formi, pa prikazano i spremljeno ne mogu doći iz različitih izvora.
 *
 * <p>OIB i naziv su iz e-Ovlaštenja (potvrđeni, uvijek prisutni). MBS i sjedište su iz OIB sustava
 * i mogu nedostajati — tada je {@code seatSource} {@code null}.
 *
 * @param registrationNumber MBS; {@code null} kad ga OIB sustav nema
 * @param seatSource         izvor MBS-a i sjedišta; {@code null} kad nisu dohvaćeni
 * @param representative     točno jedan zastupnik. Koja je osoba zna eTurizam
 *                           ({@link LegalRepresentativeSource}), inače je to NIAS osoba. Ime je iz
 *                           OIB sustava, a kad ga on ne vrati iz NIAS-a ili eTurizma
 *                           ({@code nameSource}). Adresa je iz OIB sustava i samo za NIAS osobu;
 *                           inače, ili kad je sustav ne vrati, adrese nema ({@code addressSource}
 *                           je {@code null})
 */
public record LegalEntityProfile(
        String oib,
        String name,
        String registrationNumber,
        String street,
        String streetNumber,
        String place,
        String postalCode,
        String municipality,
        String county,
        SubjectDataSource seatSource,
        SubjectProfile representative
) {
}
