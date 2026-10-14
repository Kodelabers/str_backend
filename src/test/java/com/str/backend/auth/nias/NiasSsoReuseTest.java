package com.str.backend.auth.nias;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.saml2.provider.service.authentication.DefaultSaml2AuthenticatedPrincipal;
import org.springframework.security.saml2.provider.service.authentication.Saml2Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Prijava preuzeta iz postojeće NIAS SSO sjednice (tuStart → gumb → mi) ne otvara sama odabir
 * subjekta u traci; svježa prijava i prijava nepoznate starosti otvaraju ga kao i dosad.
 */
class NiasSsoReuseTest {

    private static final String PERSON = "12312312316";
    private static final String COMPANY = "39986540678";
    private static final String SUCCESS = "https://str-test-eturizam.gov.hr/new-registration-number";
    private static final String ISSUED = "2026-10-14T10:00:00Z";

    private NavigationBarService navigationBar;
    private AuthenticationSuccessHandler handler;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        ActingSubjectService actingSubjectService = mock(ActingSubjectService.class);
        when(actingSubjectService.current(any(), any())).thenReturn(Optional.empty());
        navigationBar = new NavigationBarService(new NavigationBarProperties(true,
                "https://eusluge-nav-test.gov.hr/e_gradani.aspx", "https://str-test-eturizam.gov.hr", null),
                actingSubjectService);
        handler = new NiasSamlConfig().authenticationSuccessHandler(props(null), new NiasSessionRegistry());
        session = new MockHttpSession();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static NiasSamlProperties props(Duration threshold) {
        return new NiasSamlProperties(true, null, null, null, null, null, null, null,
                SUCCESS, null, null, null, threshold);
    }

    private static Saml2Authentication login(String authnInstant) {
        String authnStatement = authnInstant == null ? ""
                : "<saml2:AuthnStatement AuthnInstant=\"" + authnInstant + "\" SessionIndex=\"_s1\"/>";
        String xml = NiasSecurityUtilTest.response(ISSUED, authnStatement);
        Map<String, List<Object>> attrs = Map.of("oib", List.of(PERSON), "nav_token", List.of("tok-123"));
        DefaultSaml2AuthenticatedPrincipal principal = new DefaultSaml2AuthenticatedPrincipal("nameid", attrs);
        principal.setRelyingPartyRegistrationId(NiasSamlConfig.REGISTRATION_ID);
        return new Saml2Authentication(principal, xml, List.of());
    }

    /** Uspješna prijava kroz pravi success handler, pa {@code show_entity_search} prvog učitavanja trake. */
    private String showEntitySearchAfterLogin(Saml2Authentication auth) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, auth);

        assertThat(response.getRedirectedUrl()).isEqualTo(SUCCESS);
        String url = navigationBar.scriptUrl(session, auth).orElseThrow();
        String param = url.substring(url.indexOf("show_entity_search=") + "show_entity_search=".length());
        return URLDecoder.decode(param.substring(0, param.indexOf('&')), StandardCharsets.UTF_8);
    }

    @Test
    void reusedNiasSession_doesNotOpenEntitySearch() throws Exception {
        // Prijava u tuStart prije 20 minuta, NIAS nam odgovara iz iste sjednice.
        assertThat(showEntitySearchAfterLogin(login("2026-10-14T09:40:00Z"))).isEqualTo("False");
        assertThat(session.getAttribute(NavigationBarService.ENTITY_SEARCH_SHOWN_KEY)).isEqualTo(Boolean.TRUE);
    }

    @Test
    void freshLogin_opensEntitySearch() throws Exception {
        assertThat(showEntitySearchAfterLogin(login("2026-10-14T09:59:55Z"))).isEqualTo("True");
    }

    /** Bez {@code AuthnStatement} (ili šifrirana asercija) — dosadašnje ponašanje. */
    @Test
    void unknownAge_opensEntitySearch() throws Exception {
        assertThat(showEntitySearchAfterLogin(login(null))).isEqualTo("True");
    }

    /** Oznaka ranije prijave u istoj sesiji ne smije svježoj prijavi sakriti odabir. */
    @Test
    void freshLoginAfterReusedOne_opensEntitySearchAgain() throws Exception {
        showEntitySearchAfterLogin(login("2026-10-14T09:40:00Z"));

        assertThat(showEntitySearchAfterLogin(login("2026-10-14T09:59:58Z"))).isEqualTo("True");
    }

    @Test
    void reusedNiasSession_stillClearsActingSubject() throws Exception {
        session.setAttribute(ActingSubjectService.SESSION_KEY, new ActingSubject(
                COMPANY, "ADRIATIQUE GROUP D.O.O.", List.of("Direktor"), PERSON, "Pero", "Perić", Instant.EPOCH));
        session.setAttribute(ActingSubjectService.OPTIONS_KEY, "popis");
        session.setAttribute(NavigationBarService.STATE_KEY, "stari-state");

        showEntitySearchAfterLogin(login("2026-10-14T09:40:00Z"));

        assertThat(session.getAttribute(ActingSubjectService.SESSION_KEY)).isNull();
        assertThat(session.getAttribute(ActingSubjectService.OPTIONS_KEY)).isNull();
        assertThat(session.getAttribute(NavigationBarService.STATE_KEY)).isNotEqualTo("stari-state");
    }

    /** Bez automatskog dijaloga traka i dalje nudi „Promjena subjekta": povratna adresa i popis ostaju. */
    @Test
    void reusedNiasSession_keepsEntityChangeInBar() throws Exception {
        Saml2Authentication auth = login("2026-10-14T09:40:00Z");
        showEntitySearchAfterLogin(auth);

        String url = navigationBar.scriptUrl(session, auth).orElseThrow();
        assertThat(url).contains("show_entities=True").contains("change_entity_url=").contains("state%3D");
    }

    /** Prag 0 isključuje prepoznavanje: i prijava iz SSO sjednice otvara odabir kao prije. */
    @Test
    void zeroThreshold_disablesRecognition() throws Exception {
        handler = new NiasSamlConfig().authenticationSuccessHandler(props(Duration.ZERO), new NiasSessionRegistry());

        assertThat(showEntitySearchAfterLogin(login("2026-10-14T09:40:00Z"))).isEqualTo("True");
    }

    @Test
    void reusedNiasSession_onlyAboveThreshold() {
        Duration threshold = Duration.ofSeconds(60);

        assertThat(NiasSamlConfig.reusedNiasSession(Duration.ofSeconds(61), threshold)).isTrue();
        assertThat(NiasSamlConfig.reusedNiasSession(Duration.ofSeconds(60).plusMillis(1), threshold)).isTrue();
        assertThat(NiasSamlConfig.reusedNiasSession(Duration.ofSeconds(60), threshold)).isFalse();
        assertThat(NiasSamlConfig.reusedNiasSession(Duration.ofSeconds(-2), threshold)).isFalse();
        assertThat(NiasSamlConfig.reusedNiasSession(null, threshold)).isFalse();
    }

    @Test
    void zeroOrNegativeThreshold_neverReuse() {
        assertThat(NiasSamlConfig.reusedNiasSession(Duration.ofHours(5), Duration.ZERO)).isFalse();
        assertThat(NiasSamlConfig.reusedNiasSession(Duration.ofHours(5), Duration.ofSeconds(-1))).isFalse();
    }

    @Test
    void thresholdDefaultsTo60Seconds() {
        assertThat(props(null).ssoReuseThreshold()).isEqualTo(Duration.ofSeconds(60));
        assertThat(props(Duration.ofMinutes(5)).ssoReuseThreshold()).isEqualTo(Duration.ofMinutes(5));
    }

    /** Vezanje iz konfiguracije: s jedinicom, bez nje (sekunde, ne milisekunde), prazna env varijabla, bez svojstva. */
    @Test
    void thresholdBinding() {
        ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(PropsConfig.class);

        runner.withPropertyValues("nias.saml.sso-reuse-threshold=90s")
                .run(ctx -> assertThat(ctx.getBean(NiasSamlProperties.class).ssoReuseThreshold()).isEqualTo(Duration.ofSeconds(90)));
        runner.withPropertyValues("nias.saml.sso-reuse-threshold=2m")
                .run(ctx -> assertThat(ctx.getBean(NiasSamlProperties.class).ssoReuseThreshold()).isEqualTo(Duration.ofMinutes(2)));
        runner.withPropertyValues("nias.saml.sso-reuse-threshold=45")
                .run(ctx -> assertThat(ctx.getBean(NiasSamlProperties.class).ssoReuseThreshold()).isEqualTo(Duration.ofSeconds(45)));
        runner.withPropertyValues("nias.saml.sso-reuse-threshold=")
                .run(ctx -> assertThat(ctx.getBean(NiasSamlProperties.class).ssoReuseThreshold()).isEqualTo(Duration.ofSeconds(60)));
        runner.run(ctx -> assertThat(ctx.getBean(NiasSamlProperties.class).ssoReuseThreshold()).isEqualTo(Duration.ofSeconds(60)));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NiasSamlProperties.class)
    static class PropsConfig {
    }
}
