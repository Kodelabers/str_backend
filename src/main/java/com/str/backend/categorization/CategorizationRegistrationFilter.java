package com.str.backend.categorization;

/**
 * Filtar „Upis u TuRegistar" na internom popisu rješenja o kategorizaciji (MINTS 17.11., točka 1, „Objekti s
 * rješenjem koji nisu upisani"). Oba se odnose samo na prihvaćena rješenja — pravilo je u
 * {@link CategorizationDecisionRepository#REGISTRATION_FILTER}.
 */
public enum CategorizationRegistrationFilter {
    /** Prihvaćeno, objekt još nije upisan, a RB nije opozvan ni povučen — čeka „Potvrdu upisa objekta". */
    PENDING,
    /** Prihvaćeno i objekt upisan u TuRegistar (T19). */
    REGISTERED
}
