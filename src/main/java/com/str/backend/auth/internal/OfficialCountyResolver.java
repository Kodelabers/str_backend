package com.str.backend.auth.internal;

import com.str.backend.address.CountyEntity;
import com.str.backend.address.CountyNames;
import com.str.backend.address.CountyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Opseg podataka službenika iz eTurizmove sheme {@code str}: interna organizacija korisnika
 * ({@code application_user.internal_organization_id}) i nadležnost njezinih organizacijskih
 * jedinica ({@link InternalUserRepository#findOrganizations}).
 *
 * <ul>
 *   <li>ministarstvo ({@code IOT_MINTS}) — cijela RH, bez obzira na upisanu nadležnost;</li>
 *   <li>županija ({@code IOT_ZUPANIJA}) čija nadležnost pokriva točno jednu županiju — ta županija.
 *       Ide se po nadležnosti, ne po nazivu organizacije, koji nije pouzdan;</li>
 *   <li>sve ostalo — bez organizacije, više organizacija, druga vrsta (HTZ, izvođači), županija
 *       bez nadležnosti ili s više županija, greška — cijela RH (odluka naručitelja).</li>
 * </ul>
 *
 * <p>Županija se veže na {@code rpj_dgu.zupanije} preko {@code str.county.external_code} (= {@code zu_rb});
 * kad šifre nema, po nazivu ({@link CountyNames#key}).
 */
@Service
public class OfficialCountyResolver {

    static final String ORG_MINISTRY = "IOT_MINTS";
    static final String ORG_COUNTY = "IOT_ZUPANIJA";

    private static final Logger log = LoggerFactory.getLogger(OfficialCountyResolver.class);

    private final InternalUserRepository users;
    private final CountyRepository counties;

    public OfficialCountyResolver(InternalUserRepository users, CountyRepository counties) {
        this.users = users;
        this.counties = counties;
    }

    /**
     * Nikad ne baca: greška baze daje {@link CountyScope.Outcome#ERROR} (cijela RH), jer bi inače
     * svaki back-office endpoint vratio 500.
     */
    public CountyScope resolve(String oib) {
        try {
            return lookup(oib);
        } catch (RuntimeException e) {
            log.warn("internal_scope lookup_failed cause={}", e.getClass().getSimpleName());
            return CountyScope.all(CountyScope.Outcome.ERROR);
        }
    }

    private CountyScope lookup(String oib) {
        List<InternalUserRepository.Organization> found = users.findOrganizations(oib);
        if (found.isEmpty()) {
            return CountyScope.all(CountyScope.Outcome.NONE);
        }
        if (found.size() > 1) {
            // Isti OIB s više aktivnih internih zapisa u različitim organizacijama: ne zna se koja
            // vrijedi, a nasumičan odabir bi opseg mijenjao od zahtjeva do zahtjeva.
            log.warn("internal_scope several_organizations count={}", found.size());
            return CountyScope.all(CountyScope.Outcome.AMBIGUOUS);
        }
        InternalUserRepository.Organization org = found.get(0);
        if (ORG_MINISTRY.equals(org.orgType())) {
            return CountyScope.ministry(org.name());
        }
        if (!ORG_COUNTY.equals(org.orgType()) || org.counties() == 0) {
            return new CountyScope(CountyScope.Outcome.NONE, org.name(), null, null, null);
        }
        if (org.counties() > 1) {
            return new CountyScope(CountyScope.Outcome.AMBIGUOUS, org.name(), null, null, null);
        }
        Optional<CountyEntity> county = rpjCounty(org);
        if (county.isEmpty()) {
            log.warn("internal_scope unknown_county code={}", org.countyCode());
            return new CountyScope(CountyScope.Outcome.ERROR, org.name(), null, null, null);
        }
        return CountyScope.county(org.name(), county.get().getId(), county.get().getZuRb(), county.get().getName());
    }

    private Optional<CountyEntity> rpjCounty(InternalUserRepository.Organization org) {
        Integer zuRb = parseZuRb(org.countyCode());
        if (zuRb != null) {
            Optional<CountyEntity> byCode = counties.findFirstByZuRb(zuRb);
            if (byCode.isPresent()) {
                return byCode;
            }
        }
        String key = CountyNames.key(org.countyName());
        return key == null ? Optional.empty() : counties.findAllByOrderByZuRb().stream()
                .filter(c -> key.equals(CountyNames.key(c.getName())))
                .findFirst();
    }

    private static Integer parseZuRb(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(code.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
