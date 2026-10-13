package com.str.backend.address;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NonEuCountryPolicyTest {

    /**
     * Tripwire for the not-yet-delivered MINTS list: while {@link EuEftaMembership#EU_WITHOUT_EIDAS}
     * is empty, the production policy behaves exactly like plain EU/EFTA exclusion for both
     * purposes.
     *
     * <p><b>When the list arrives</b> this test fails on purpose. Replace it with one that pins the
     * real members (each listed state is NOT excluded as {@code PREBIVALISTE} and IS excluded as
     * {@code SJEDISTE}; all other EU/EFTA states stay excluded), and add a new Liquibase changeset
     * (context {@code local}) for any listed state missing from the 20-country local seed
     * {@code 104-str-country-restore-local.xml}; on dev/CDU check {@code str.country}
     * ({@code active}, {@code iso2_alpha}). See the javadoc of {@code EU_WITHOUT_EIDAS}.
     */
    @Test
    void production_policy_matches_plain_membership_while_exception_list_is_empty() {
        assertThat(EuEftaMembership.EU_WITHOUT_EIDAS).isEmpty();

        NonEuCountryPolicy policy = new NonEuCountryPolicy();
        for (String code : EuEftaMembership.ISO2_CODES) {
            assertThat(policy.isExcluded(code, CountryPurpose.PREBIVALISTE)).as(code).isTrue();
            assertThat(policy.isExcluded(code, CountryPurpose.SJEDISTE)).as(code).isTrue();
        }
        for (String code : new String[] {"RS", "US", "GB", "EL"}) {
            assertThat(policy.isExcluded(code, CountryPurpose.PREBIVALISTE)).as(code).isFalse();
            assertThat(policy.isExcluded(code, CountryPurpose.SJEDISTE)).as(code).isFalse();
        }
    }

    /** "RO" is test data only — it does not reflect the real MINTS list. */
    @Test
    void exception_applies_to_residence_only_never_to_seat() {
        NonEuCountryPolicy policy = NonEuCountryPolicy.withEuWithoutEidas(Set.of("RO"));

        assertThat(policy.isExcluded("RO", CountryPurpose.PREBIVALISTE)).isFalse();
        assertThat(policy.isExcluded(" ro ", CountryPurpose.PREBIVALISTE)).isFalse();
        assertThat(policy.isExcluded("RO", CountryPurpose.SJEDISTE)).isTrue();

        assertThat(policy.isExcluded("DE", CountryPurpose.PREBIVALISTE)).isTrue();
        assertThat(policy.isExcluded("HR", CountryPurpose.PREBIVALISTE)).isTrue();
        assertThat(policy.isExcluded("CH", CountryPurpose.PREBIVALISTE)).isTrue();
        assertThat(policy.isExcluded("RS", CountryPurpose.SJEDISTE)).isFalse();
    }

    @Test
    void null_or_blank_iso_is_never_excluded() {
        NonEuCountryPolicy policy = NonEuCountryPolicy.withEuWithoutEidas(Set.of("RO"));
        for (CountryPurpose purpose : CountryPurpose.values()) {
            assertThat(policy.isExcluded(null, purpose)).as(purpose.name()).isFalse();
            assertThat(policy.isExcluded("  ", purpose)).as(purpose.name()).isFalse();
        }
    }

    @Test
    void invalid_exception_list_is_refused() {
        assertThatThrownBy(() -> NonEuCountryPolicy.withEuWithoutEidas(Set.of("HR")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NonEuCountryPolicy.withEuWithoutEidas(Set.of("NO")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void purpose_is_required() {
        assertThatThrownBy(() -> new NonEuCountryPolicy().isExcluded("DE", null))
                .isInstanceOf(NullPointerException.class);
    }
}
