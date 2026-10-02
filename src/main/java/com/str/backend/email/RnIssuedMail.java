package com.str.backend.email;

/**
 * Obavijest o izdanom registracijskom broju.
 *
 * <p>Iznajmljivač s OIB-om dobiva samo obavijest: obavijest o dodjeli dostavlja se u korisnički
 * pretinac, pa poruka nema privitak. Non-EU iznajmljivač pretinac nema i njemu je e-pošta kanal
 * dostave — za njega {@code pdf} nosi sam akt, obavijest o dodjeli registracijskog broja.
 *
 * @param objekt         naziv i adresa smještajne jedinice; za non-EU poruku se ne koristi
 * @param pdf            dokument koji se dostavlja; {@code null} kad je {@code dostavaMailom} false
 * @param dostavaMailom  je li e-pošta kanal dostave (non-EU) ili samo obavijest
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
