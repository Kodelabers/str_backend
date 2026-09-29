package com.str.backend.auth.nias;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redak {@code nias_login} je prva dijagnostika e-Ovlaštenja na okolini: stiže li {@code sesija_id}
 * i u kojem obliku — ali nikad njegova vrijednost.
 */
class NiasLoginLogTest {

    @Test
    void sesijaIdShape_describesWithoutValue() {
        String shape = NiasSamlConfig.sesijaIdShape("3B51-9acb-EAE9-801A");

        assertThat(shape).isEqualTo("len=19 znakovi=[-, 0-9, A-Z, a-z]");
        assertThat(shape).doesNotContain("3B51");
    }

    /** Znakovi izvan {@code [A-Za-z0-9-]} navode se pojedinačno — po njima se širi uzorak zahtjeva. */
    @Test
    void sesijaIdShape_listsUnexpectedCharacters() {
        assertThat(NiasSamlConfig.sesijaIdShape("ab+/=")).isEqualTo("len=5 znakovi=[+, /, =, a-z]");
    }

    @Test
    void sesijaIdShape_missing() {
        assertThat(NiasSamlConfig.sesijaIdShape(null)).isEqualTo("nema");
        assertThat(NiasSamlConfig.sesijaIdShape("  ")).isEqualTo("nema");
    }
}
