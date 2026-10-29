package com.str.backend.auth.internal;

import com.str.backend.accommodation.AccommodationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Opseg podataka prijavljenog korisnika ({@link CountyScope}) — jedino mjesto iz kojeg ga čitaju
 * back-office endpointi. Ograničenje provodi backend: županija iz opsega prepisuje filtar
 * županije iz zahtjeva, a RB iz druge županije službeniku je nepostojeći (404).
 *
 * <p>Utvrđuje se na svakom pozivu, bez keša, kao i rola ({@link InternalAccessService}): promjena
 * organizacije službenika u eTurizmu djeluje odmah. To je ponovno čitanje role, upit organizacije
 * ({@link OfficialCountyResolver}) i, za ograničenog službenika, županija iz {@code rpj_dgu} te
 * popis zapisanih naziva županija ({@link #accommodationCounties}) — svi mali, na tablicama
 * od nekoliko stotina do nekoliko desetaka tisuća redaka.
 */
@Service
public class CountyScopeService {

    private static final Logger log = LoggerFactory.getLogger(CountyScopeService.class);

    private final InternalAccessService internalAccess;
    private final OfficialCountyResolver resolver;
    private final AccommodationRepository accommodations;

    public CountyScopeService(InternalAccessService internalAccess, OfficialCountyResolver resolver,
                              AccommodationRepository accommodations) {
        this.internalAccess = internalAccess;
        this.resolver = resolver;
        this.accommodations = accommodations;
    }

    public CountyScope scopeFor(Authentication authentication) {
        Optional<String> official = internalAccess.officialOib(authentication);
        if (official.isEmpty()) {
            return CountyScope.all(CountyScope.Outcome.NOT_OFFICIAL);
        }
        CountyScope scope = resolver.resolve(official.get());
        if (log.isDebugEnabled()) {
            log.debug("internal_scope outcome={} zu_rb={}", scope.outcome(), scope.zuRb() != null ? scope.zuRb() : "-");
        }
        return scope;
    }

    /**
     * Nazivi županije opsega kako su zapisani na smještaju ({@code accommodation.county}), za
     * filtre registra, statistike i aktivnosti; {@code null} = cijela RH.
     */
    public List<String> accommodationCounties(Authentication authentication) {
        CountyScope scope = scopeFor(authentication);
        return scope.restricted() ? scope.matching(accommodations.findDistinctCountiesOrderByName()) : null;
    }

    /**
     * Kao {@link #accommodationCounties(Authentication)}, uz filtar županije iz zahtjeva za onoga
     * tko nije ograničen: tražena županija u svim zapisanim oblicima, kao što je broji STR dashboard
     * (pa popis RB-ova županije odgovara broju u njezinu retku). Ograničenom službeniku zahtjev ne
     * mijenja ništa; {@code null} = bez filtra.
     */
    public List<String> accommodationCounties(Authentication authentication, String requestedCounty) {
        List<String> scoped = accommodationCounties(authentication);
        if (scoped != null || requestedCounty == null || requestedCounty.isBlank()) {
            return scoped;
        }
        return CountyScope.county(null, null, null, requestedCounty.trim())
                .matching(accommodations.findDistinctCountiesOrderByName());
    }
}
