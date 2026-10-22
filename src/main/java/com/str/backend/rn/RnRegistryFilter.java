package com.str.backend.rn;

/**
 * Filtri registra RB-ova ({@code GET /api/rn} i tri izvoza) — isti skup ide kroz kontroler,
 * {@link RnRegistrySearch}, {@link RnService} i izvoz, pa se ne prenosi kao četrnaest argumenata.
 *
 * <p>{@code decision} i {@code verified} su stupci „Rješenje o kategorizaciji" i „Verificiran";
 * {@code null} znači bez filtra.
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
        Boolean verified
) {}
