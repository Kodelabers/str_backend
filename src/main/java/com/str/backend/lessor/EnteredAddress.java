package com.str.backend.lessor;

/**
 * Adresa podnositelja (ili sjedište tvrtke) i MBS koje je korisnik upisao na obrascu, u našem
 * obliku — neovisno o DTO-u zahtjeva, da {@code lessor} ne ovisi o {@code registration}.
 *
 * <p>Koristi se samo kad registar podatak nema ({@link SubjectProfileService#resolveLessor},
 * {@link SubjectProfileService#resolveLegalLessor}); tada je i obavezan.
 *
 * <p>Id-evi razina adresnog registra (iz kaskade {@code /api/address/**}) mjerodavni su za
 * županiju, općinu i naselje: prije upotrebe adresa prolazi {@link EnteredAddressVerifier}, koji
 * provjerava lanac i nazive uzima iz registra. Ulica i kućni broj smiju biti upisani tekstom kad
 * ih registar nema ({@code streetId} / {@code houseNumberId} {@code null}).
 *
 * @param county             naziv županije iz šifrarnika — istim oblikom GO-1 uspoređuje županiju
 *                           objekta
 * @param registrationNumber MBS tvrtke; za fizičku osobu {@code null}
 */
public record EnteredAddress(
        String street,
        String streetNumber,
        String postalCode,
        String place,
        String municipality,
        String county,
        String registrationNumber,
        Long countyId,
        Long municipalityId,
        Long settlementId,
        Long streetId,
        Long houseNumberId
) {

    /** Adresa bez id-eva registra (samo tekst) — za provjeru lanca ona je nepotpuna. */
    public EnteredAddress(String street, String streetNumber, String postalCode, String place,
                          String municipality, String county, String registrationNumber) {
        this(street, streetNumber, postalCode, place, municipality, county, registrationNumber,
                null, null, null, null, null);
    }
}
