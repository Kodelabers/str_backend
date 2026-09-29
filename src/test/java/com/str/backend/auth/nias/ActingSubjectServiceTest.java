package com.str.backend.auth.nias;

import com.str.backend.exception.BusinessException;
import com.str.backend.registries.eovlastenja.EOvlastenjaClient;
import com.str.backend.registries.eovlastenja.EOvlastenjaException;
import com.str.backend.registries.eovlastenja.Zastupanje;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.saml2.provider.service.authentication.DefaultSaml2AuthenticatedPrincipal;
import org.springframework.security.saml2.provider.service.authentication.Saml2Authentication;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Subjekt se u sesiju sprema samo nakon potvrde e-Ovlaštenja i vrijedi samo za istu osobu. */
class ActingSubjectServiceTest {

    private static final String PERSON = "70000000004";
    private static final String COMPANY = "33333333360";
    private static final NiasIdentity IDENTITY = new NiasIdentity(PERSON, "Ana", "Horvat", "sesija-1", "TID1");

    private final EOvlastenjaClient client = mock(EOvlastenjaClient.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-29T10:00:00Z"), ZoneOffset.UTC);
    private final ActingSubjectService service = new ActingSubjectService(client, clock);
    private final MockHttpSession session = new MockHttpSession();

    private static Zastupanje zastupanje() {
        return new Zastupanje(PERSON, "ANA", "HORVAT", COMPANY, "TESTNA TVRTKA d.o.o.",
                List.of(new Zastupanje.Funkcija("034", "Direktor", "0")));
    }

    @Test
    void select_storesVerifiedSubject() {
        when(client.verifyRepresentation("sesija-1", PERSON, COMPANY)).thenReturn(zastupanje());

        ActingSubject s = service.select(session, IDENTITY, " " + COMPANY + " ");

        assertThat(s.legalOib()).isEqualTo(COMPANY);
        assertThat(s.legalName()).isEqualTo("TESTNA TVRTKA d.o.o.");
        assertThat(s.functions()).containsExactly("Direktor");
        assertThat(s.representativeOib()).isEqualTo(PERSON);
        assertThat(s.representativeFirstName()).isEqualTo("Ana"); // ime iz NIAS-a ima prednost
        assertThat(s.verifiedAt()).isEqualTo(Instant.parse("2026-09-29T10:00:00Z"));
        assertThat(service.current(session, PERSON)).contains(s);
    }

    @Test
    void select_rejected_storesNothing() {
        when(client.verifyRepresentation(any(), any(), any())).thenThrow(new EOvlastenjaException(
                EOvlastenjaException.Reason.NOT_REPRESENTATIVE, null, "ne"));

        assertThatThrownBy(() -> service.select(session, IDENTITY, COMPANY)).isInstanceOf(EOvlastenjaException.class);
        assertThat(service.current(session, PERSON)).isEmpty();
    }

    @Test
    void select_invalidOib_doesNotCallEOvlastenja() {
        assertThatThrownBy(() -> service.select(session, IDENTITY, "12345678901"))
                .isInstanceOf(BusinessException.class).hasMessage("error.actingSubject.invalidOib");
        assertThatThrownBy(() -> service.select(session, IDENTITY, PERSON))
                .isInstanceOf(BusinessException.class).hasMessage("error.actingSubject.self");
        verifyNoInteractions(client);
    }

    /** Subjekt odabran za jednu osobu ne vrijedi ako se u istoj sesiji pojavi druga. */
    @Test
    void current_ignoresSubjectOfAnotherPerson() {
        when(client.verifyRepresentation(any(), any(), any())).thenReturn(zastupanje());
        service.select(session, IDENTITY, COMPANY);

        assertThat(service.current(session, "55555555551")).isEmpty();
        assertThat(service.current(null, PERSON)).isEmpty();
    }

    @Test
    void reverify_failure_clearsSubject() {
        when(client.verifyRepresentation(any(), any(), any())).thenReturn(zastupanje());
        ActingSubject s = service.select(session, IDENTITY, COMPANY);
        when(client.verifyRepresentation(any(), any(), any())).thenThrow(new EOvlastenjaException(
                EOvlastenjaException.Reason.SESSION, "200", "istekla"));

        assertThatThrownBy(() -> service.reverify(session, IDENTITY, s)).isInstanceOf(EOvlastenjaException.class);
        assertThat(service.current(session, PERSON)).isEmpty();
    }

    /** Efektivni OIB je OIB tvrtke samo dok je subjekt odabran — inače OIB osobe. */
    @Test
    void effectiveOib_followsActingSubject() {
        NiasOibResolver niasOibResolver = mock(NiasOibResolver.class);
        Authentication auth = mock(Authentication.class);
        when(niasOibResolver.resolve(auth)).thenReturn(Optional.of(PERSON));
        EffectiveOibResolver resolver = new EffectiveOibResolver(niasOibResolver, service);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            assertThat(resolver.resolve(auth)).contains(PERSON);

            when(client.verifyRepresentation(any(), any(), any())).thenReturn(zastupanje());
            service.select(session, IDENTITY, COMPANY);
            assertThat(resolver.resolve(auth)).contains(COMPANY);
            assertThat(resolver.actingSubject(auth)).map(ActingSubject::legalOib).contains(COMPANY);

            service.clear(session);
            assertThat(resolver.resolve(auth)).contains(PERSON);
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    /**
     * Radnja u ime tvrtke (izdavanje/povlačenje RB-a, upload) ponovo zove e-Ovlaštenja, sa
     * sjednicom iz <b>trenutnog</b> assertiona; neuspjeh briše subjekt. U svoje ime — nema poziva.
     */
    @Test
    void reverifiedActingSubject_callsEOvlastenjaAgain_onlyWhenActingForCompany() {
        Authentication auth = samlAuth("sesija-2");
        NiasOibResolver niasOibResolver = mock(NiasOibResolver.class);
        when(niasOibResolver.resolve(auth)).thenReturn(Optional.of(PERSON));
        EffectiveOibResolver resolver = new EffectiveOibResolver(niasOibResolver, service);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            assertThat(resolver.reverifiedActingSubject(auth)).isEmpty();
            verifyNoInteractions(client);

            when(client.verifyRepresentation(any(), any(), any())).thenReturn(zastupanje());
            service.select(session, IDENTITY, COMPANY);
            assertThat(resolver.reverifiedActingSubject(auth)).map(ActingSubject::legalOib).contains(COMPANY);
            verify(client, times(1)).verifyRepresentation("sesija-2", PERSON, COMPANY);

            when(client.verifyRepresentation(any(), any(), any())).thenThrow(new EOvlastenjaException(
                    EOvlastenjaException.Reason.NOT_REPRESENTATIVE, "400", "ne"));
            assertThatThrownBy(() -> resolver.reverifiedActingSubject(auth)).isInstanceOf(EOvlastenjaException.class);
            assertThat(resolver.actingSubject(auth)).isEmpty();
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private static Authentication samlAuth(String sesijaId) {
        DefaultSaml2AuthenticatedPrincipal principal = new DefaultSaml2AuthenticatedPrincipal(
                "persistent-nameid", Map.of(
                        "oib", List.<Object>of(PERSON),
                        "ime", List.<Object>of("Ana"),
                        "prezime", List.<Object>of("Horvat"),
                        "sesija_id", List.<Object>of(sesijaId)));
        return new Saml2Authentication(principal, "<saml2p:Response/>", List.of());
    }
}
