package com.str.backend.auth.nias;

import com.str.backend.exception.BusinessException;
import com.str.backend.exception.ExternalRegistryException;
import com.str.backend.registries.eovlastenja.EOvlastenjaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.saml2.provider.service.authentication.DefaultSaml2AuthenticatedPrincipal;
import org.springframework.security.saml2.provider.service.authentication.Saml2Authentication;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NavigationBarServiceTest {

    private static final String PERSON = "12312312316";
    private static final String COMPANY = "39986540678";
    private static final String BASE = "https://str-test-eturizam.gov.hr";
    private static final String SCRIPT = "https://eusluge-nav-test.gov.hr/e_gradani.aspx";
    private static final String SAML_RESPONSE =
            "<saml2p:Response xmlns:saml2p=\"urn:oasis:names:tc:SAML:2.0:protocol\" ID=\"_r\" InResponseTo=\"ARQ-42\"/>";

    private ActingSubjectService actingSubjectService;
    private NavigationBarService service;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        actingSubjectService = mock(ActingSubjectService.class);
        service = new NavigationBarService(
                new NavigationBarProperties(true, SCRIPT, BASE + "/", null), actingSubjectService);
        session = new MockHttpSession();
        when(actingSubjectService.current(any(), any())).thenReturn(Optional.empty());
    }

    private static Saml2Authentication saml(String samlResponse, String... namesAndValues) {
        Map<String, List<Object>> attrs = new HashMap<>();
        attrs.put("oib", List.of(PERSON));
        for (int i = 0; i < namesAndValues.length; i += 2) {
            attrs.put(namesAndValues[i], List.<Object>of(namesAndValues[i + 1]));
        }
        return new Saml2Authentication(new DefaultSaml2AuthenticatedPrincipal("nameid", attrs), samlResponse, List.of());
    }

    private static Saml2Authentication loggedIn() {
        return saml(SAML_RESPONSE, "nav_token", " tok-123 ");
    }

    private static Map<String, String> query(String url) {
        Map<String, String> params = new HashMap<>();
        String q = url.substring(url.indexOf('?') + 1);
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            params.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return params;
    }

    // ── adresa skripte ─────────────────────────────────────────────────────

    @Test
    void scriptUrl_carriesLoginPairingAndOurChangeEntityUrl() {
        String url = service.scriptUrl(session, loggedIn()).orElseThrow();

        assertThat(url).startsWith(SCRIPT + "?");
        Map<String, String> p = query(url);
        assertThat(p).containsEntry("messageId", "ARQ-42")
                .containsEntry("navToken", "tok-123")
                .containsEntry("login_url", BASE + "/saml2/authenticate/nias")
                .containsEntry("show_entities", "True")
                .containsEntry("ToLegalIps", "");
        String state = (String) session.getAttribute(NavigationBarService.STATE_KEY);
        assertThat(state).isNotBlank();
        // Zamjenske oznake traka popunjava sama; & unutar vrijednosti je kodiran, pa ne razbija URL skripte.
        assertThat(p.get("change_entity_url")).isEqualTo(BASE + "/api/nias/acting-subject/change-entity"
                + "?toLegalIps={ToLegalIps}&toLegalIzvorReg={ToLegalIzvor_reg}&forPersonOib={ForPersonOib}&state=" + state);
        assertThat(url).doesNotContain("{ToLegalIps}");
    }

    @Test
    void scriptUrl_stateIsStableWithinSession() {
        String first = query(service.scriptUrl(session, loggedIn()).orElseThrow()).get("change_entity_url");
        String second = query(service.scriptUrl(session, loggedIn()).orElseThrow()).get("change_entity_url");
        assertThat(first).isEqualTo(second);
    }

    @Test
    void scriptUrl_marksCurrentlySelectedCompany() {
        when(actingSubjectService.current(any(), eq(PERSON))).thenReturn(Optional.of(new ActingSubject(
                COMPANY, "ADRIATIQUE GROUP D.O.O.", List.of("Direktor"), PERSON, "Pero", "Perić", Instant.EPOCH)));

        Map<String, String> p = query(service.scriptUrl(session, loggedIn()).orElseThrow());

        assertThat(p).containsEntry("ToLegalIps", COMPANY).containsEntry("ToLegalIzvor_reg", "1");
    }

    @Test
    void scriptUrl_opensEntitySearchOnlyOnFirstLoadAfterLogin() {
        String first = query(service.scriptUrl(session, loggedIn()).orElseThrow()).get("show_entity_search");
        String reload = query(service.scriptUrl(session, loggedIn()).orElseThrow()).get("show_entity_search");

        assertThat(first).isEqualTo("True");
        // Traka bi inače dijalog otvarala pri svakom učitavanju, i nakon povratka s odabira.
        assertThat(reload).isEqualTo("False");
    }

    @Test
    void scriptUrl_doesNotOpenEntitySearchWhenSubjectAlreadySelected() {
        when(actingSubjectService.current(any(), eq(PERSON))).thenReturn(Optional.of(new ActingSubject(
                COMPANY, "ADRIATIQUE GROUP D.O.O.", List.of("Direktor"), PERSON, "Pero", "Perić", Instant.EPOCH)));

        Map<String, String> p = query(service.scriptUrl(session, loggedIn()).orElseThrow());
        assertThat(p).containsEntry("show_entity_search", "False");

        // Ni povratak u svoje ime kasnije u istoj prijavi ne otvara ga ponovno.
        when(actingSubjectService.current(any(), eq(PERSON))).thenReturn(Optional.empty());
        assertThat(query(service.scriptUrl(session, loggedIn()).orElseThrow())).containsEntry("show_entity_search", "False");
    }

    @Test
    void scriptUrl_newLoginOpensEntitySearchAgain() {
        service.scriptUrl(session, loggedIn());
        // Isto što NiasSamlConfig radi pri novoj prijavi.
        session.removeAttribute(NavigationBarService.ENTITY_SEARCH_SHOWN_KEY);

        assertThat(query(service.scriptUrl(session, loggedIn()).orElseThrow())).containsEntry("show_entity_search", "True");
    }

    @Test
    void scriptUrl_withoutNavToken_isEmpty() {
        assertThat(service.scriptUrl(session, saml(SAML_RESPONSE))).isEmpty();
    }

    @Test
    void scriptUrl_withoutInResponseTo_isEmpty() {
        assertThat(service.scriptUrl(session, saml("<saml2p:Response xmlns:saml2p=\"urn:oasis:names:tc:SAML:2.0:protocol\"/>",
                "nav_token", "tok"))).isEmpty();
    }

    @Test
    void scriptUrl_disabled_isEmpty() {
        NavigationBarService disabled = new NavigationBarService(
                new NavigationBarProperties(false, null, null, null), actingSubjectService);
        assertThat(disabled.scriptUrl(session, loggedIn())).isEmpty();
    }

    @Test
    void scriptUrl_nonSamlLogin_isEmpty() {
        assertThat(service.scriptUrl(session, new UsernamePasswordAuthenticationToken(PERSON, null, List.of()))).isEmpty();
    }

    @Test
    void enabledWithoutScriptUrl_failsAtStartup() {
        assertThatThrownBy(() -> new NavigationBarService(
                new NavigationBarProperties(true, "", BASE, null), actingSubjectService))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── povratak iz trake ─────────────────────────────────────────────────

    private String validState() {
        service.scriptUrl(session, loggedIn());
        return (String) session.getAttribute(NavigationBarService.STATE_KEY);
    }

    private String change(String ips, String izvorReg, String forPersonOib, String state) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        URI uri = service.change(request, loggedIn(), ips, izvorReg, forPersonOib, state);
        assertThat(uri.toString()).startsWith(BASE + "/existing-objects?entitySwitch=");
        return UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst("entitySwitch");
    }

    /** Odabir iz trake učitane u ovoj sesiji (svježi state, kao nakon ponovnog učitavanja stranice). */
    private String pick(String ips, String izvorReg, String forPersonOib) {
        return change(ips, izvorReg, forPersonOib, validState());
    }

    @Test
    void change_company_selectsThroughSignedCheck() {
        assertThat(pick(COMPANY, "1", "")).isEqualTo("ok");

        verify(actingSubjectService).select(eq(session), eq(new NiasIdentity(PERSON, null, null)), eq(COMPANY));
    }

    @Test
    void change_withoutState_isRejectedWithoutFinaCall() {
        validState();

        assertThat(change(COMPANY, "1", null, null)).isEqualTo("invalid");
        assertThat(change(COMPANY, "1", null, "podmetnuto")).isEqualTo("invalid");
        assertThat(change("", "", null, "AAAAAAAAAAAAAAAAAAAAAA")).isEqualTo("invalid");

        verify(actingSubjectService, never()).select(any(), any(), any());
        verify(actingSubjectService, never()).clear(any());
    }

    @Test
    void change_stateIsSingleUse() {
        String state = validState();

        assertThat(change(COMPANY, "1", null, state)).isEqualTo("ok");
        // Ista poveznica ponovo (npr. iz loga ili povijesti preglednika) više ništa ne mijenja.
        assertThat(change("", "", null, state)).isEqualTo("invalid");

        verify(actingSubjectService, never()).clear(any());
        assertThat(validState()).isNotEqualTo(state);
    }

    @Test
    void change_self_clearsSubject() {
        assertThat(pick("", "", "")).isEqualTo("self");
        assertThat(pick(null, null, PERSON)).isEqualTo("self");
        assertThat(pick(PERSON, null, null)).isEqualTo("self");

        verify(actingSubjectService, times(3)).clear(session);
        verify(actingSubjectService, never()).select(any(), any(), any());
    }

    @Test
    void change_otherPerson_isUnsupported_andKeepsSubject() {
        assertThat(pick("", "", "98765432106")).isEqualTo("unsupported");

        verify(actingSubjectService, never()).clear(any());
        verify(actingSubjectService, never()).select(any(), any(), any());
    }

    @Test
    void change_otherRegistry_isUnsupported() {
        assertThat(pick(COMPANY, "2", null)).isEqualTo("unsupported");
        verify(actingSubjectService, never()).select(any(), any(), any());
    }

    @Test
    void change_missingRegistry_stillGoesToSignedCheck() {
        assertThat(pick(COMPANY, null, null)).isEqualTo("ok");
        verify(actingSubjectService).select(any(), any(), eq(COMPANY));
    }

    @Test
    void change_mapsRejections() {
        when(actingSubjectService.select(any(), any(), any()))
                .thenThrow(new EOvlastenjaException(EOvlastenjaException.Reason.NOT_REPRESENTATIVE, "400", "ne"))
                .thenThrow(new EOvlastenjaException(EOvlastenjaException.Reason.SESSION, "200", "istekla"))
                .thenThrow(new EOvlastenjaException(EOvlastenjaException.Reason.SUBJECT_NOT_FOUND, "500", "nema"));

        assertThat(pick(COMPANY, "1", null)).isEqualTo("notRepresentative");
        assertThat(pick(COMPANY, "1", null)).isEqualTo("sessionExpired");
        assertThat(pick(COMPANY, "1", null)).isEqualTo("notFound");
    }

    @Test
    void change_mapsLocalAndInfrastructureFailures() {
        when(actingSubjectService.select(any(), any(), any()))
                .thenThrow(new BusinessException("error.actingSubject.invalidOib"))
                .thenThrow(new ActingSubjectRateLimitException(30))
                .thenThrow(new ExternalRegistryException("EOVLASTENJA", "nedostupno"))
                .thenThrow(new IllegalStateException("neočekivano"));

        assertThat(pick("123", "1", null)).isEqualTo("invalid");
        assertThat(pick(COMPANY, "1", null)).isEqualTo("rateLimited");
        assertThat(pick(COMPANY, "1", null)).isEqualTo("unavailable");
        assertThat(pick(COMPANY, "1", null)).isEqualTo("unavailable");
    }

    @Test
    void change_withoutLogin_redirectsToLogin() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        URI uri = service.change(request, null, COMPANY, "1", null, "x");

        assertThat(uri.toString()).isEqualTo(BASE + "/existing-objects?entitySwitch=loginRequired");
        verify(actingSubjectService, never()).select(any(), any(), any());
    }

    // ── adresa skripte samo s FINA-e ──────────────────────────────────────

    @Test
    void scriptUrl_onlyFinaHosts() {
        assertThat(NavigationBarProperties.isFinaScript("https://eusluge-nav-test.gov.hr/e_gradani.aspx")).isTrue();
        assertThat(NavigationBarProperties.isFinaScript("https://eusluge-nav.gov.hr/e_gradani.aspx")).isTrue();
        assertThat(NavigationBarProperties.isFinaScript("http://eusluge-nav.gov.hr/e_gradani.aspx")).isFalse();
        assertThat(NavigationBarProperties.isFinaScript("https://eusluge-nav.gov.hr.zlo.hr/e_gradani.aspx")).isFalse();
        assertThat(NavigationBarProperties.isFinaScript("https://eusluge-nav.gov.hr@zlo.hr/e_gradani.aspx")).isFalse();
        assertThat(NavigationBarProperties.isFinaScript("https://eusluge-nav.gov.hr:8443/e_gradani.aspx")).isFalse();
        assertThat(NavigationBarProperties.isFinaScript("https://cdn.example.com/x.js")).isFalse();
        assertThatThrownBy(() -> new NavigationBarService(
                new NavigationBarProperties(true, "https://cdn.example.com/x.js", BASE, null), actingSubjectService))
                .isInstanceOf(IllegalStateException.class);
    }
}
