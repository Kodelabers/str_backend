package com.str.backend.domain;

/**
 * Razlog povlačenja registracijskog broja po službenoj dužnosti (MINTS, konačan popis), redom kojim
 * se nudi u obrascu.
 *
 * <p>Nije {@link RnTrigger}: razlog se bira na prijedlogu povlačenja (T12), čiji prijelaz u
 * {@link RnStatus#WITHDRAWAL_PROPOSED} uvijek nosi okidač {@link RnTrigger#WITHDRAWAL_PROPOSAL}, o kojem ovise
 * akt, eGOP i obavijesti. Razlog se zato ne upisuje u {@code trigger_name}, nego kao tekst u
 * {@code registration_number_log.reason} zapisa prijedloga — natpis iz {@code documents/hr/labels.properties}
 * ({@code povlacenje.razlog.*}), a uz {@link #OTHER} opis službenika. Akti prijedloga, obustave i povlačenja
 * (istekom roka, {@link RnTrigger#WITHDRAWAL_DEADLINE_EXCEEDED}) ga odande ispisuju, kao i razlog suspenzije.
 */
public enum WithdrawalReason {
    /** Namjerno ili krajnjom nepažnjom nisu ispravljeni podaci. */
    DATA_NOT_CORRECTED,
    /** Dostavljene su nevjerodostojne ili nevaljane informacije. */
    INVALID_INFORMATION,
    /** Drugi razlozi — opis je obvezan i on se ispisuje u aktu. */
    OTHER;

    public boolean requiresNote() {
        return this == OTHER;
    }
}
