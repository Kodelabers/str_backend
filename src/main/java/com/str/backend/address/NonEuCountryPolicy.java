package com.str.backend.address;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;

/**
 * Decides which countries are kept out of the non-EU registration flow, per
 * {@link CountryPurpose}. The two call sites — the dropdown
 * ({@code AddressLookupService.findCountries}, {@code GET /api/address/countries}) and the
 * server-side guard ({@code LessorRegistrationService.requireActiveCountry}) — both go
 * through this bean, so they cannot drift apart.
 *
 * <ul>
 *   <li>{@link CountryPurpose#PREBIVALISTE}: EU/EFTA excluded, except EU states without
 *       eIDAS;</li>
 *   <li>{@link CountryPurpose#SJEDISTE}: every EU/EFTA state excluded.</li>
 * </ul>
 *
 * <p>The exception list has one source of truth, {@link EuEftaMembership#EU_WITHOUT_EIDAS};
 * Spring uses the no-arg constructor, which reads it. {@link #withEuWithoutEidas(Set)} exists
 * so tests of the callers can substitute a non-empty list while the real one is empty.
 */
@Component
public class NonEuCountryPolicy {

    private final Set<String> euWithoutEidas;

    public NonEuCountryPolicy() {
        this(EuEftaMembership.EU_WITHOUT_EIDAS);
    }

    private NonEuCountryPolicy(Set<String> euWithoutEidas) {
        this.euWithoutEidas = EuEftaMembership.requireValidEuWithoutEidas(euWithoutEidas);
    }

    /**
     * Policy with an explicit exception list — for tests only; production code gets the
     * Spring bean, which uses {@link EuEftaMembership#EU_WITHOUT_EIDAS}. The same validation
     * applies (upper-case EU codes, never HR).
     */
    public static NonEuCountryPolicy withEuWithoutEidas(Set<String> euWithoutEidas) {
        return new NonEuCountryPolicy(euWithoutEidas);
    }

    /**
     * @return {@code true} if a country with this ISO 3166-1 alpha-2 code must not be offered
     * or accepted for the given purpose; {@code false} for {@code null} or blank codes
     * (treated as outside the EU/EFTA area, as before)
     */
    public boolean isExcluded(String iso2, CountryPurpose purpose) {
        Objects.requireNonNull(purpose, "purpose");
        return switch (purpose) {
            case PREBIVALISTE -> EuEftaMembership.excludedFromNonEuFlow(iso2, euWithoutEidas);
            case SJEDISTE -> EuEftaMembership.isEuOrEfta(iso2);
        };
    }
}
