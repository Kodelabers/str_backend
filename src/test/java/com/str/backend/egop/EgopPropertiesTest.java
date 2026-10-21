package com.str.backend.egop;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EgopPropertiesTest {

    @Test
    void appUser_withDomain_isDomainBackslashUser() {
        assertThat(properties("INFODOM", "strservis").qualifiedAppUsername()).isEqualTo("INFODOM\\strservis");
    }

    /** egopeaitest.mint.hr: aplikacijski korisnik nema domenu (21.10.2026.). */
    @Test
    void appUser_withoutDomain_isJustUser() {
        assertThat(properties("", "strservis").qualifiedAppUsername()).isEqualTo("strservis");
        assertThat(properties(null, "strservis").qualifiedAppUsername()).isEqualTo("strservis");
        assertThatCode(() -> properties("", "strservis").requireComplete()).doesNotThrowAnyException();
    }

    /** Ugašen eGOP bez env varijabli ne smije srušiti start (EgopAktKorisnik čita korisnika odmah). */
    @Test
    void appUser_missing_isEmptyNotException() {
        assertThat(properties("", null).qualifiedAppUsername()).isEmpty();
    }

    @Test
    void appUser_isStillRequired() {
        assertThatThrownBy(() -> properties("", " ").requireComplete())
                .hasMessageContaining("app-username");
    }

    private static EgopProperties properties(String appDomain, String appUsername) {
        return new EgopProperties(true, "ntlm-user", "ntlm-pass", appDomain, appUsername,
                null, null, "https://egopeaitest.mint.hr", null, null, null, null);
    }
}
