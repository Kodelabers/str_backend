package com.str.backend.domain;

/**
 * Napredak sinkronizacije submissiona s eGOP-om. Svaki uspješan korak se odmah
 * perzistira, pa retry nastavlja od zadnjeg dovršenog koraka umjesto da duplicira
 * subjekte/predmete (eGOP nema idempotency ključeve).
 */
public enum EgopSyncStatus {
    NEW,
    SUBJEKT_OK,
    PREDMET_OK,
    PISMENO_OK,
    SYNCED,
    FAILED
}
