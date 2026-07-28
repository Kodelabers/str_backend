package com.str.backend.address;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EuEftaMembershipTest {

    @Test
    void returns_true_for_eu_codes_case_insensitive() {
        assertThat(EuEftaMembership.isEuOrEfta("DE")).isTrue();
        assertThat(EuEftaMembership.isEuOrEfta("de")).isTrue();
        assertThat(EuEftaMembership.isEuOrEfta(" hr ")).isTrue();
        assertThat(EuEftaMembership.isEuOrEfta("IT")).isTrue();
    }

    /**
     * EFTA states are not EU members, but their nationals do not belong in the non-EU
     * registration flow either — they must be filtered out of the country list and
     * rejected by the server-side guard just like EU states.
     */
    @Test
    void returns_true_for_all_four_efta_codes() {
        assertThat(EuEftaMembership.isEuOrEfta("IS")).isTrue();
        assertThat(EuEftaMembership.isEuOrEfta("LI")).isTrue();
        assertThat(EuEftaMembership.isEuOrEfta("NO")).isTrue();
        assertThat(EuEftaMembership.isEuOrEfta("CH")).isTrue();
    }

    @Test
    void returns_false_for_codes_outside_eu_and_efta() {
        assertThat(EuEftaMembership.isEuOrEfta("RS")).isFalse();
        assertThat(EuEftaMembership.isEuOrEfta("US")).isFalse();
        assertThat(EuEftaMembership.isEuOrEfta("GB")).isFalse();
        assertThat(EuEftaMembership.isEuOrEfta("BA")).isFalse();
    }

    /** Grčka je u str.country zapisana kao GR, a ne kao EU-ova varijanta EL. */
    @Test
    void matches_greece_by_iso_code_not_eu_variant() {
        assertThat(EuEftaMembership.isEuOrEfta("GR")).isTrue();
        assertThat(EuEftaMembership.isEuOrEfta("EL")).isFalse();
    }

    /** 27 EU + 4 EFTA — čuva od tihog ispadanja koda pri budućem uređivanju popisa. */
    @Test
    void covers_all_twenty_seven_eu_and_four_efta_states() {
        assertThat(EuEftaMembership.ISO2_CODES).hasSize(31);
    }

    @Test
    void returns_false_for_null_or_blank() {
        assertThat(EuEftaMembership.isEuOrEfta(null)).isFalse();
        assertThat(EuEftaMembership.isEuOrEfta("")).isFalse();
        assertThat(EuEftaMembership.isEuOrEfta("   ")).isFalse();
    }
}
