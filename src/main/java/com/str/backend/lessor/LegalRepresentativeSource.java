package com.str.backend.lessor;

import java.util.Optional;

/**
 * Tko je zakonski zastupnik tvrtke, po eTurizmu ({@code str.document.subject_representative_id}).
 * Ime i adresu zastupnika daje {@link LegalEntityRegistry}; ovdje se dobiva samo koja je to osoba.
 */
public interface LegalRepresentativeSource {

    /**
     * Točno jedan zastupnik, deterministički: prednost ima {@code preferredOib} (NIAS osoba koja
     * podnosi zahtjev) ako ga tvrtka ima među zastupnicima, inače zastupnik s najnovijeg dokumenta.
     *
     * @return zastupnik, ili {@link Optional#empty()} kad tvrtka u eTurizmu nema dokument sa zastupnikom
     */
    Optional<Representative> findRepresentative(String legalOib, String preferredOib);

    /** Ime i prezime kako ih vodi eTurizam — rezerva kad ih OIB sustav ne vrati. */
    record Representative(String oib, String firstName, String lastName) {
    }
}
