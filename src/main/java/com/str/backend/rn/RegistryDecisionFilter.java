package com.str.backend.rn;

/**
 * Filtar stupca „Rješenje o kategorizaciji" na registru ({@code GET /api/rn?decision=}). Vrijednosti
 * su ono što stupac prikazuje, isto kao „Moji registracijski brojevi" (FE {@code shownDecisionStatus}):
 * status mjerodavnog rješenja kad postoji (potvrđeno, pa predano, pa odbijeno), inače
 * {@link #NONE} uz RB koji rješenje traži (novi objekt, broj nije opozvan ni povučen), a
 * {@link #NOT_REQUIRED} uz sve ostale. Pravilo je u {@link RnRepository#CATEGORIZATION_SHOWN}.
 */
public enum RegistryDecisionFilter {
    /** „Nije predano" — rješenje je potrebno, a nema ga. */
    NONE,
    SUBMITTED,
    VERIFIED,
    REJECTED,
    /** „Nije potrebno" — objekt iz eTurizma ili konačan broj, bez ijednog rješenja. */
    NOT_REQUIRED
}
