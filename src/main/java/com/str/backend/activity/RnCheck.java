package com.str.backend.activity;

/**
 * Valjanost registracijskog broja u prijavljenoj aktivnosti, u trenutku primitka (T7). Platforma
 * smije prijaviti i broj koji nije naš, pa ga STR sprema i označava umjesto da odbije paket. Iz
 * oznaka nastaje popis nevažećih brojeva.
 */
public enum RnCheck {
    /** Broj postoji i vrijedi (ACTIVE, SUSPENSION_PROPOSED ili WITHDRAWAL_PROPOSED koji nije pokrenut iz suspenzije). */
    VALID,
    /** Nije u obliku {@code HR} + 18 znamenki. */
    INVALID_FORMAT,
    /** Ispravnog oblika, ali ga nema u registru. */
    UNKNOWN,
    /** Postoji, ali ne vrijedi (suspendiran — i prijedlog povlačenja iz suspenzije —, opozvan ili povučen). */
    NOT_ACTIVE
}
