package com.str.backend.auth.nias;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API pozivi bez sesije dobivaju 401, a ne 302 na NIAS prijavu — fetch bi preusmjerenje pratio
 * do HTML-a NIAS-a. Prijava ({@code /saml2/**}) mora i dalje ići preusmjerenjem.
 *
 * <p>Cijeli NIAS lanac ovdje se ne diže (traži SAML certifikate); testira se matcher koji odlučuje
 * koja ulazna točka vrijedi.
 */
class NiasSecurityConfigApiMatcherTest {

    @Test
    void matchesApiCalls() {
        assertThat(NiasSecurityConfig.API_REQUESTS.matches(request("", "/api/nias/subject"))).isTrue();
        assertThat(NiasSecurityConfig.API_REQUESTS.matches(request("", "/api/generateRegistrationNumber"))).isTrue();
        assertThat(NiasSecurityConfig.API_REQUESTS.matches(
                request("", "/api/generateRegistrationNumber/0b7c/pdf"))).isTrue();
    }

    @Test
    void doesNotMatchSamlLogin() {
        assertThat(NiasSecurityConfig.API_REQUESTS.matches(request("", "/saml2/authenticate/nias"))).isFalse();
        assertThat(NiasSecurityConfig.API_REQUESTS.matches(request("", "/login/saml2/sso/nias"))).isFalse();
    }

    @Test
    void respectsContextPath() {
        assertThat(NiasSecurityConfig.API_REQUESTS.matches(request("/str", "/str/api/nias/subject"))).isTrue();
        assertThat(NiasSecurityConfig.API_REQUESTS.matches(request("/str", "/api/nias/subject"))).isFalse();
    }

    private static MockHttpServletRequest request(String contextPath, String uri) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", uri);
        r.setContextPath(contextPath);
        return r;
    }
}
