package com.str.backend.egop;

import com.str.backend.lessor.LessorEntity;

import java.util.Optional;

/**
 * Čiji korisnički pretinac prima izlazno pismeno.
 *
 * <ul>
 *   <li>fizička osoba — iznajmljivač, po {@code lessorOib};</li>
 *   <li>pravna osoba — <b>zastupnik iz zahtjeva</b> ({@code representativeOib}, snapshot uz
 *       iznajmljivača). Odluka od 19. 10. 2026.: akt životnog ciklusa može nastati mjesecima
 *       kasnije, ali ide zastupniku koji je podnio zahtjev, ne trenutnom;</li>
 *   <li>iznajmljivač bez OIB-a (non-EU, i fizička i pravna osoba) — nema pretinca; dostava
 *       ostaje e-poštom, i kad zastupnik slučajno ima OIB, jer bi isti akt inače bio dostavljen
 *       dvama kanalima s dva različita početka roka.</li>
 * </ul>
 */
public final class KpPrimatelj {

    private KpPrimatelj() {
    }

    public static Optional<String> resolve(LessorEntity lessor) {
        if (isBlank(lessor.getLessorOib())) {
            return Optional.empty();
        }
        String oib = lessor.isLegalEntityOwner() ? lessor.getRepresentativeOib() : lessor.getLessorOib();
        return isBlank(oib) ? Optional.empty() : Optional.of(oib.strip());
    }

    /** Pravna osoba s OIB-om, a bez zastupnika — ne bi se smjelo dogoditi, pa se prijavljuje glasno. */
    public static boolean isAnomaly(LessorEntity lessor) {
        return lessor.isLegalEntityOwner() && !isBlank(lessor.getLessorOib()) && resolve(lessor).isEmpty();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
