package com.str.backend.egop;

import java.time.Instant;

/**
 * Stanje poruke u korisničkom pretincu, kako ga vrati {@code DohvatiKPStatusPorukeSaVremenima}.
 * Vremena su {@code null} dok se odgovarajuća aktivnost nije dogodila.
 *
 * @param statusPoruke 0 bez aktivnosti, 1 suglasnost, 2 potvrđen primitak, 3 odbijeno,
 *                     4 odustao od preuzimanja
 */
public record KpStatusPoruke(int statusPoruke,
                             Instant kreirano,
                             Instant poslano,
                             Instant zaprimljenoNias,
                             Instant proslijedenoPrimatelju,
                             Instant zaprimljenoPrimatelj,
                             Instant zadnjaProvjeraAktivnosti,
                             Instant zadnjaAktivnost) {
}
