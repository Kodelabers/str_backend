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
 *
 * <p>Radni popisi internog portala (MINTS 17.11., točka 1): {@code eturizamOnly} ostavlja samo RB-ove objekata
 * iz eTurizma (uz {@code verified=false} „Neverificirani objekti", bez novih objekata); {@code decisionMissing}
 * aktivne RB-ove novog objekta bez prihvaćenog ili predanog rješenja (nije predano ili je odbijeno), a
 * {@code decisionOverdue} od njih samo one kojima je rok za rješenje istekao ({@link CategorizationDeadline}).
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
        List<String> scopeCounties,
        boolean eturizamOnly,
        boolean decisionMissing,
        boolean decisionOverdue
) {

    /** Registar bez filtara radnih popisa — izvozi i dosadašnji pozivi. */
    public RnRegistryFilter(RnRegistryView view, String q, String county, String municipality, Long typeId,
                            boolean foreignOnly, String rb, String city, String street, String name, String lessor,
                            Integer deadlineWithinDays, RegistryDecisionFilter decision, Boolean verified,
                            List<String> scopeCounties) {
        this(view, q, county, municipality, typeId, foreignOnly, rb, city, street, name, lessor, deadlineWithinDays,
                decision, verified, scopeCounties, false, false, false);
    }

    /** Isti filtri uz filtre radnih popisa; „istekao rok" vrijedi samo uz {@code decisionMissing}. */
    public RnRegistryFilter withWorklist(boolean eturizamOnly, boolean decisionMissing, boolean decisionOverdue) {
        return new RnRegistryFilter(view, q, county, municipality, typeId, foreignOnly, rb, city, street, name, lessor,
                deadlineWithinDays, decision, verified, scopeCounties, eturizamOnly, decisionMissing,
                decisionMissing && decisionOverdue);
    }
}
