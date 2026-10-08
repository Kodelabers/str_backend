package com.str.backend.lessor;

/**
 * Adresa podnositelja (ili sjedište tvrtke) i MBS koje je korisnik upisao na obrascu, u našem
 * obliku — neovisno o DTO-u zahtjeva, da {@code lessor} ne ovisi o {@code registration}.
 *
 * <p>Koristi se samo kad registar podatak nema ({@link SubjectProfileService#resolveLessor},
 * {@link SubjectProfileService#resolveLegalLessor}); tada je i obavezan.
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
        String registrationNumber
) {
}
