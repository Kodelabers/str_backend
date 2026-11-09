package com.str.backend.email;

/**
 * Obavijest o izdanom registracijskom broju.
 *
 * <p>Iznajmljivaču s OIB-om akt se dostavlja u korisnički pretinac, a poruka nosi njegovu presliku.
 * Non-EU iznajmljivač pretinac nema i njemu je e-pošta kanal dostave. U oba slučaja {@code pdf} je
 * obavijest o dodjeli registracijskog broja.
 *
 * @param objekt         naziv i adresa smještajne jedinice; za non-EU poruku se ne koristi
 * @param pdf            obavijest o dodjeli; {@code null} samo iznajmljivaču s OIB-om, kad verzije
 *                       akta iz eGOP-a nema ni nakon čekanja (poruka tada ide bez privitka)
 * @param dostavaMailom  je li e-pošta kanal dostave (non-EU) ili obavijest uz dostavu u pretinac
 */
public record RnIssuedMail(
        String to,
        String ime,
        String rn,
        String objekt,
        byte[] pdf,
        boolean dostavaMailom
) {
}
