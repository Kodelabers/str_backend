package com.str.backend.rn;

import com.str.backend.exception.BusinessException;
import com.str.backend.exception.ExternalRegistryException;
import com.str.backend.rn.dto.RnSummaryDto;
import com.str.backend.str.RnFacilityVerification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Registar RB-ova sa stupcem i filtrom „Verificiran". Verifikacija objekta nije u str_rn nego u
 * eTurizmu ({@link RnFacilityVerification}), pa se registar slaže u koracima:
 *
 * <ol>
 *   <li>s filtrom „Verificiran": eTurizam objekti RB-ova koje ostali filtri propuštaju
 *       ({@link RnService#registryFacilityIds}) → koji su od njih verificirani → taj skup ide u
 *       upit registra, pa paginacija i ukupan broj ostaju točni. eTurizam nedostupan → 503:
 *       filtar se ne smije tiho preskočiti;</li>
 *   <li>upit registra ({@link RnService#searchRegistry});</li>
 *   <li>stupac za prikazane redove: uz filtar je vrijednost poznata iz prvog koraka, inače se
 *       pita eTurizam za objekte stranice. eTurizam nedostupan → stupac {@code null} („-"):
 *       registar ne smije ovisiti o dostupnosti eTurizma.</li>
 * </ol>
 *
 * <p>Namjerno bez {@code @Transactional}, iz istog razloga kao {@link RnFacilityVerification}:
 * iznimka iz eTurizam repozitorija označila bi vanjsku transakciju kao rollback-only, pa bi i
 * uhvaćena greška završila kao {@code UnexpectedRollbackException}. Svaki korak ima svoju.
 */
@Service
public class RnRegistrySearch {

    private static final Logger log = LoggerFactory.getLogger(RnRegistrySearch.class);

    /**
     * Najviše verificiranih objekata koje filtar „Verificiran" šalje u upit registra. Lista ide
     * kao {@code IN}, a PostgreSQL JDBC prima najviše 32.767 parametara po upitu; preko toga se
     * traži uži filtar, kao kod izvoza od 50.000 redaka.
     */
    static final int MAX_VERIFIED_FACILITIES = 30_000;

    private final RnService rnService;
    private final RnFacilityVerification facilityVerification;

    public RnRegistrySearch(RnService rnService, RnFacilityVerification facilityVerification) {
        this.rnService = rnService;
        this.facilityVerification = facilityVerification;
    }

    public Page<RnSummaryDto> search(RnRegistryFilter filter, Pageable pageable) {
        Page<RnSummaryDto> page = rnService.searchRegistry(filter, verifiedFacilityIds(filter), pageable);
        return new PageImpl<>(withFacilityVerified(filter, page.getContent()),
                page.getPageable(), page.getTotalElements());
    }

    /**
     * Redovi za izvoz. Preko {@code maxRows} odbija prije stupca „Verificiran" — da se eTurizam ne
     * pita za desetke tisuća objekata izvoza koji se ionako neće napraviti.
     */
    public List<RnSummaryDto> searchForExport(RnRegistryFilter filter, int maxRows) {
        List<RnSummaryDto> rows = rnService.searchRegistryForExport(filter, verifiedFacilityIds(filter), maxRows);
        if (rows.size() > maxRows) {
            throw new BusinessException("error.export.too.many.rows");
        }
        return withFacilityVerified(filter, rows);
    }

    private Set<String> verifiedFacilityIds(RnRegistryFilter filter) {
        if (filter.verified() == null) {
            return Set.of();
        }
        // Izvan try-a: greška vlastite baze nije nedostupan eTurizam i ne smije postati 503 s njegovim imenom.
        List<String> facilityIds = rnService.registryFacilityIds(filter);
        Set<String> verified;
        try {
            verified = facilityVerification.verifiedFacilityIds(facilityIds);
        } catch (DataAccessException e) {
            throw new ExternalRegistryException("eTurizam", "Verifikacija objekata nije dostupna", e);
        }
        if (verified.size() > MAX_VERIFIED_FACILITIES) {
            log.warn("rn_registry_verified_filter too_many verified={}", verified.size());
            throw new BusinessException("error.registry.verified.too.many");
        }
        return verified;
    }

    private List<RnSummaryDto> withFacilityVerified(RnRegistryFilter filter, List<RnSummaryDto> rows) {
        if (filter.verified() != null) {
            // Filtar je već odlučio: svi preostali redovi imaju traženu vrijednost.
            return rows.stream().map(row -> row.withFacilityVerified(filter.verified())).toList();
        }
        Set<String> verified;
        try {
            verified = facilityVerification.verifiedFacilityIds(
                    rows.stream().map(RnSummaryDto::facilityId).filter(Objects::nonNull).toList());
        } catch (DataAccessException e) {
            return rows;
        }
        return rows.stream()
                .map(row -> row.withFacilityVerified(
                        row.facilityId() != null && verified.contains(row.facilityId())))
                .toList();
    }
}
