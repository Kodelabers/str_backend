package com.str.backend.categorization;

/**
 * Životni ciklus uploadanog skena rješenja o kategorizaciji.
 *
 * <p>{@code SUBMITTED} → objekt se na korisnikovom popisu prikazuje kao privremeno rješenje,
 * bez RB-a. {@code VERIFIED} postavlja nadležno tijelo kad zapis prihvati; tek tada objekt
 * ide u eTurizam i dobiva {@code facility_id}. {@code REJECTED} je konačno odbijanje.
 */
public enum CategorizationDecisionStatus {
    SUBMITTED,
    VERIFIED,
    REJECTED
}
