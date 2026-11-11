package com.str.backend.rn.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

/**
 * Odgovor {@code GET /api/rn/{rn}/detail}. JSON je ravan: polja {@link RnDetailDto} i
 * {@link RnDetailExtras} su na vrhu objekta, kao što je FE dobivao i prije proširenja. Zato
 * se imena komponenti tih dvaju recorda ne smiju preklapati (Jackson bi ispisao dva ista ključa).
 *
 * @param lessorAddressKind što je adresa iz {@link RnDetailExtras} ({@code lessorStreet} …);
 *                          {@code null} kad iznajmljivač nije poznat
 * @param categorization    mjerodavno rješenje o kategorizaciji (T19); {@code null} kad ga nema
 */
public record RnDetailResponse(
        @JsonUnwrapped RnDetailDto detail,
        @JsonUnwrapped RnDetailExtras extras,
        LessorAddressKind lessorAddressKind,
        RnCategorizationInfo categorization
) {

    public enum LessorAddressKind {
        /** Prebivalište fizičke osobe; za non-EU pravnu osobu stalna adresa osobe koja je registrirana. */
        RESIDENCE,
        /** Sjedište pravne osobe (e-Zastupanja, NIAS) — upisano u iste stupce kao prebivalište. */
        SEAT
    }

    /**
     * Non-EU pravna osoba (bez OIB-a) ima sjedište u {@code legalEntityCity} / {@code legalEntityCountryName},
     * a u stupcima adrese stalnu adresu osobe sa samoregistracije. Samo NIAS pravna osoba tamo ima sjedište.
     */
    public static RnDetailResponse of(RnDetailDto detail, RnDetailExtras extras) {
        return of(detail, extras, null);
    }

    public static RnDetailResponse of(RnDetailDto detail, RnDetailExtras extras, RnCategorizationInfo categorization) {
        LessorAddressKind kind = null;
        if (detail.lessorId() != null) {
            boolean niasLegalEntity = Boolean.TRUE.equals(detail.legalEntityOwner()) && detail.lessorOib() != null;
            kind = niasLegalEntity ? LessorAddressKind.SEAT : LessorAddressKind.RESIDENCE;
        }
        return new RnDetailResponse(detail, extras, kind, categorization);
    }
}
