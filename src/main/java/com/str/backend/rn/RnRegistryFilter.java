package com.str.backend.rn;

import java.util.List;

/**
 * Filtri registra RB-ova ({@code GET /api/rn} i tri izvoza) — isti skup ide kroz kontroler,
 * {@link RnRegistrySearch}, {@link RnService} i izvoz, pa se ne prenosi kao četrnaest argumenata.
 *
 * <p>{@code decision} i {@code verified} su stupci „Rješenje o kategorizaciji" i „Verificiran";
 * {@code null} znači bez filtra.
 *
 * <p>{@code scopeCounties} je opseg službenika ({@link com.str.backend.auth.internal.CountyScope#matching}):
 * nazivi njegove županije kako su zapisani na smještaju, ili {@code null} za cijelu RH. Ne dolazi
 * iz zahtjeva, nego ga kontroler postavlja iz sesije.
 */
public record RnRegistryFilter(
        RnRegistryView view,
        String q,
        String county,
        String municipality,
        Long typeId,
        boolean foreignOnly,
        String rb,
        String city,
        String street,
        String name,
        String lessor,
        Integer deadlineWithinDays,
        RegistryDecisionFilter decision,
        Boolean verified,
        List<String> scopeCounties
) {}
