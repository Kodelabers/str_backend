package com.str.backend.kp;

import com.str.backend.lessor.LessorEntity;

import java.util.Optional;

/**
 * Čiji korisnički pretinac prima izlazno pismeno.
 *
 * <ul>
 *   <li>fizička osoba — KP građana (FO), po {@code lessorOib};</li>
 *   <li>pravna osoba — <b>poslovni KP tvrtke</b> (PS), po JIPS-u: IPS je OIB tvrtke
 *       ({@code lessorOib} nakon {@code applyNiasLegalEntity}), izvor je registar OIB-a.
 *       Odluka od 21. 10. 2026.; do tada je akt išao u osobni pretinac zastupnika;</li>
 *   <li>iznajmljivač bez OIB-a (non-EU) — nema pretinca; dostava ostaje e-poštom.</li>
 * </ul>
 */
public record KpPrimatelj(Tip tip, String id, String izvorRegistar) {

    public enum Tip { FO, PS }

    /**
     * @param izvorRegistarOib oznaka registra OIB-a pravnih osoba u JIPS-u
     *                         ({@code str.kp.izvor-registar-oib})
     */
    public static Optional<KpPrimatelj> resolve(LessorEntity lessor, String izvorRegistarOib) {
        String oib = lessor.getLessorOib();
        if (oib == null || oib.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(lessor.isLegalEntityOwner()
                ? new KpPrimatelj(Tip.PS, oib.strip(), izvorRegistarOib)
                : new KpPrimatelj(Tip.FO, oib.strip(), null));
    }
}
