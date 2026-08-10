package com.str.backend.auth.nias;

/** Odakle red na popisu dolazi — bitno frontendu, jer privremeni zapis nema ni RB ni sve podatke. */
public enum FacilitySource {

    /** Objekt iz eTurizam registra ({@code str.facility}). */
    ETURIZAM,

    /**
     * Objekt koji za sada postoji samo kao uploadano skenirano rješenje
     * ({@code str_rn.categorization_decision}), do upisa u eTurizam.
     */
    PRIVREMENO_RJESENJE
}
