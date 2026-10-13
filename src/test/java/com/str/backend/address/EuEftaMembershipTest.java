package com.str.backend.address;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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


    // --- excludedFromNonEuFlow: pravilo za zemlju prebivališta (EU članice bez eIDAS-a) ---
    // Stvarni popis i "tripwire" test za njega su u NonEuCountryPolicyTest.

    @Test
    void empty_exception_set_keeps_all_eu_and_efta_excluded() {
        for (String code : EuEftaMembership.ISO2_CODES) {
            assertThat(EuEftaMembership.excludedFromNonEuFlow(code, Set.of())).as(code).isTrue();
        }
        assertThat(EuEftaMembership.excludedFromNonEuFlow("BA", Set.of())).isFalse();
        assertThat(EuEftaMembership.excludedFromNonEuFlow("EL", Set.of())).isFalse();
    }

    /** "RO" je samo testni podatak — ne odražava stvarni popis MINTS-a. */
    @Test
    void eu_state_in_exception_set_is_not_excluded_others_still_are() {
        Set<String> exceptions = Set.of("RO");

        assertThat(EuEftaMembership.excludedFromNonEuFlow("RO", exceptions)).isFalse();
        assertThat(EuEftaMembership.excludedFromNonEuFlow("ro", exceptions)).isFalse();
        assertThat(EuEftaMembership.excludedFromNonEuFlow(" Ro ", exceptions)).isFalse();

        assertThat(EuEftaMembership.excludedFromNonEuFlow("DE", exceptions)).isTrue();
        assertThat(EuEftaMembership.excludedFromNonEuFlow("hr", exceptions)).isTrue();
        assertThat(EuEftaMembership.excludedFromNonEuFlow("CH", exceptions)).isTrue();
        assertThat(EuEftaMembership.excludedFromNonEuFlow("RS", exceptions)).isFalse();

        // Iznimka ne mijenja članstvo.
        assertThat(EuEftaMembership.isEuOrEfta("RO")).isTrue();
    }

    /** Hrvatska nikad nije iznimka, čak ni kad bi se greškom našla u skupu. */
    @Test
    void croatia_stays_excluded_even_if_listed_as_exception() {
        assertThat(EuEftaMembership.excludedFromNonEuFlow("HR", Set.of("HR"))).isTrue();
        assertThat(EuEftaMembership.excludedFromNonEuFlow(" hr ", Set.of("HR", "RO"))).isTrue();
    }

    @Test
    void excluded_from_non_eu_flow_is_false_for_null_or_blank() {
        assertThat(EuEftaMembership.excludedFromNonEuFlow(null, Set.of())).isFalse();
        assertThat(EuEftaMembership.excludedFromNonEuFlow("", Set.of())).isFalse();
        assertThat(EuEftaMembership.excludedFromNonEuFlow("   ", Set.of())).isFalse();
        assertThat(EuEftaMembership.excludedFromNonEuFlow(null, Set.of("RO"))).isFalse();
        assertThat(EuEftaMembership.excludedFromNonEuFlow("  ", Set.of("RO"))).isFalse();
    }

    /**
     * Čuva budući unos popisa: samo EU članice (ne EFTA, ne treće zemlje, ne "EL", nikad HR),
     * zapisane velikim slovima kako ih normalizacija uspoređuje.
     */
    @Test
    void eu_without_eidas_is_an_upper_case_subset_of_eu_members_without_croatia() {
        assertThat(EuEftaMembership.EU_ISO2_CODES).containsAll(EuEftaMembership.EU_WITHOUT_EIDAS);
        assertThat(EuEftaMembership.EU_WITHOUT_EIDAS)
                .doesNotContain("HR")
                .doesNotContainAnyElementsOf(EuEftaMembership.EFTA_ISO2_CODES)
                .allSatisfy(code -> assertThat(code).isEqualTo(code.trim().toUpperCase(Locale.ROOT)));
    }

    @Test
    void exception_list_validation_rejects_croatia_efta_third_countries_and_lower_case() {
        assertThat(EuEftaMembership.requireValidEuWithoutEidas(Set.of("RO", "BG"))).containsExactlyInAnyOrder("RO", "BG");
        assertThat(EuEftaMembership.requireValidEuWithoutEidas(Set.of())).isEmpty();
        for (String bad : List.of("HR", "CH", "NO", "RS", "EL", "ro")) {
            assertThatThrownBy(() -> EuEftaMembership.requireValidEuWithoutEidas(Set.of(bad)))
                    .as(bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(bad);
        }
    }

    @Test
    void eu_and_efta_sets_are_disjoint_and_sized_27_and_4() {
        assertThat(EuEftaMembership.EU_ISO2_CODES).hasSize(27);
        assertThat(EuEftaMembership.EFTA_ISO2_CODES).hasSize(4);
        assertThat(EuEftaMembership.EU_ISO2_CODES)
                .doesNotContainAnyElementsOf(EuEftaMembership.EFTA_ISO2_CODES);
    }
}
