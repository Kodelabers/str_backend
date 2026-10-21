package com.str.backend.auth.nias;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.saml2.provider.service.authentication.AbstractSaml2AuthenticationRequest;
import org.springframework.security.saml2.provider.service.authentication.Saml2PostAuthenticationRequest;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;

import static org.assertj.core.api.Assertions.assertThat;

/** Put iz {@code ?returnTo=} putuje sesijom do ACS-a i tamo prelazi u request za success handler. */
class SessionReturnToAuthenticationRequestRepositoryTest {

    private final SessionReturnToAuthenticationRequestRepository repo = new SessionReturnToAuthenticationRequestRepository();
    private final MockHttpSession session = new MockHttpSession();

    private static AbstractSaml2AuthenticationRequest authnRequest() {
        return Saml2PostAuthenticationRequest
                .withRelyingPartyRegistration(RelyingPartyRegistration.withRegistrationId("nias")
                        .entityId("CN=Test")
                        .assertionConsumerServiceLocation("https://localhost/login/saml2/sso/nias")
                        .assertingPartyDetails(p -> p
                                .entityId("https://nias.gov.hr")
                                .singleSignOnServiceLocation("https://nias.gov.hr/sso-http"))
                        .build())
                .samlRequest("PHNhbWxwOkF1dGhuUmVxdWVzdC8+")
                .authenticationRequestUri("https://nias.gov.hr/sso-http")
                .id("ARQ-1")
                .build();
    }

    private MockHttpServletRequest start(String returnTo) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/saml2/authenticate/nias");
        request.setSession(session);
        if (returnTo != null) {
            request.addParameter(NiasReturnTo.PARAM, returnTo);
        }
        return request;
    }

    private MockHttpServletRequest acs() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login/saml2/sso/nias");
        request.setSession(session);
        return request;
    }

    @Test
    void returnTo_reachesRequestOnAcs() {
        repo.saveAuthenticationRequest(authnRequest(), start("/moji-rb?x=1"), new MockHttpServletResponse());

        MockHttpServletRequest acs = acs();
        assertThat(repo.removeAuthenticationRequest(acs, new MockHttpServletResponse())).isNotNull();

        assertThat(acs.getAttribute(NiasReturnTo.REQUEST_ATTR)).isEqualTo("/moji-rb?x=1");
        assertThat(session.getAttribute(NiasReturnTo.SESSION_KEY)).isNull();
    }

    @Test
    void invalidReturnTo_isIgnored() {
        repo.saveAuthenticationRequest(authnRequest(), start("//evil.com"), new MockHttpServletResponse());

        MockHttpServletRequest acs = acs();
        repo.removeAuthenticationRequest(acs, new MockHttpServletResponse());

        assertThat(acs.getAttribute(NiasReturnTo.REQUEST_ATTR)).isNull();
    }

    /** Napuštena prijava s putem ne smije svoj put podmetnuti sljedećoj, pokrenutoj bez njega. */
    @Test
    void newLoginWithoutReturnTo_dropsPreviousOne() {
        repo.saveAuthenticationRequest(authnRequest(), start("/stari-put"), new MockHttpServletResponse());
        repo.saveAuthenticationRequest(authnRequest(), start(null), new MockHttpServletResponse());

        MockHttpServletRequest acs = acs();
        repo.removeAuthenticationRequest(acs, new MockHttpServletResponse());

        assertThat(acs.getAttribute(NiasReturnTo.REQUEST_ATTR)).isNull();
    }

    @Test
    void acsWithoutStoredRequest_setsNothing() {
        session.setAttribute(NiasReturnTo.SESSION_KEY, "/put");

        MockHttpServletRequest acs = acs();
        assertThat(repo.removeAuthenticationRequest(acs, new MockHttpServletResponse())).isNull();

        assertThat(acs.getAttribute(NiasReturnTo.REQUEST_ATTR)).isNull();
    }
}
