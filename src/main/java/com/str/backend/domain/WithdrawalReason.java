package com.str.backend.domain;

/**
 * Razlog povlačenja registracijskog broja po službenoj dužnosti (MINTS, konačan popis), redom kojim
 * se nudi u obrascu.
 *
 * <p>Nije {@link RnTrigger}: prijelaz u {@link RnStatus#WITHDRAWN} uvijek nosi okidač
 * {@link RnTrigger#WITHDRAWAL}, o kojem ovise akt, eGOP i obavijesti. Razlog se zato ne upisuje u
 * {@code trigger_name}, nego kao tekst u {@code registration_number_log.reason} — natpis iz
 * {@code documents/hr/labels.properties} ({@code povlacenje.razlog.*}), a uz {@link #OTHER} opis
 * službenika. Akt povlačenja ga odande ispisuje kao i razlog suspenzije.
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
