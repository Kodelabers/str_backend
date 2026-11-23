package com.str.backend.rn;

/**
 * Izvor objekta uz RB ({@code GET /api/rn?objectSource=}): {@link #ETURIZAM} su objekti iz eTurizma (smještaj
 * nosi {@code facility_id}). Popis „Neverificirani objekti" (MINTS 17.11., točka 1) ga šalje uz
 * {@code verified=false}, da RB-ovi novih objekata — koji su uvijek „neverificirani" — ne uđu u popis.
 */
public enum RegistryObjectSource {
    ETURIZAM
}
