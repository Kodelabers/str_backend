package com.str.backend.address;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EuMembershipTest {

    @Test
    void returns_true_for_eu_codes_case_insensitive() {
        assertThat(EuMembership.isEu("DE")).isTrue();
        assertThat(EuMembership.isEu("de")).isTrue();
        assertThat(EuMembership.isEu(" hr ")).isTrue();
        assertThat(EuMembership.isEu("IT")).isTrue();
    }

    @Test
    void returns_false_for_non_eu_codes() {
        assertThat(EuMembership.isEu("RS")).isFalse();
        assertThat(EuMembership.isEu("US")).isFalse();
        assertThat(EuMembership.isEu("CH")).isFalse();
        assertThat(EuMembership.isEu("GB")).isFalse();
    }

    @Test
    void returns_false_for_null_or_blank() {
        assertThat(EuMembership.isEu(null)).isFalse();
        assertThat(EuMembership.isEu("")).isFalse();
        assertThat(EuMembership.isEu("   ")).isFalse();
    }
}
