package com.str.backend.egop.codebook;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Vrste poslovnih subjekata u eGOP-u po nazivu, kako bi se preko MDM šifrarnika
 * ({@code ListaVrstePoslovnihSubjekata}) mogao razriješiti ID.
 */
@Getter
@AllArgsConstructor
public enum VrstaPoslovnihSubjekata {

    PRAVNA_OSOBA("Pravna osoba"),
    FIZICKA_OSOBA("Fizička osoba");

    private final String naziv;
}
