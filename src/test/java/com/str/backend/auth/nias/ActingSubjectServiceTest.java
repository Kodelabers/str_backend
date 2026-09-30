package com.str.backend.auth.nias;

import com.str.backend.exception.BusinessException;
import com.str.backend.exception.ExternalRegistryException;
import com.str.backend.registries.eovlastenja.EOvlastenjaClient;
import com.str.backend.registries.eovlastenja.EOvlastenjaException;
import com.str.backend.registries.eovlastenja.ZastupanaTvrtka;
import com.str.backend.registries.eovlastenja.Zastupanje;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.saml2.provider.service.authentication.DefaultSaml2AuthenticatedPrincipal;
import org.springframework.security.saml2.provider.service.authentication.Saml2Authentication;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    private final ActingSubjectService service =
            new ActingSubjectService(client, new ActingSubjectRateLimiter(100, 100, clock), clock);
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

    /** Neispravan i vlastiti OIB odbijaju se lokalno i ne troše kvotu — ni FINA-in poziv ni ograničenje. */
    @Test
    void locallyRejectedOibs_doNotConsumeQuota() {
        ActingSubjectService strict = new ActingSubjectService(client, new ActingSubjectRateLimiter(1, 1, clock), clock);
        when(client.verifyRepresentation(any(), any(), any())).thenReturn(zastupanje());

        assertThatThrownBy(() -> strict.select(session, IDENTITY, "12345678901")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> strict.select(session, IDENTITY, PERSON)).isInstanceOf(BusinessException.class);

        assertThat(strict.select(session, IDENTITY, COMPANY).legalOib()).isEqualTo(COMPANY);
    }

    /** Preko kvote nema poziva FINA-i. */
    @Test
    void overQuota_isRejectedBeforeEOvlastenja() {
        ActingSubjectService strict = new ActingSubjectService(client, new ActingSubjectRateLimiter(1, 1, clock), clock);
        when(client.verifyRepresentation(any(), any(), any())).thenReturn(zastupanje());
        strict.select(session, IDENTITY, COMPANY);

        assertThatThrownBy(() -> strict.select(session, IDENTITY, COMPANY))
                .isInstanceOf(ActingSubjectRateLimitException.class);
        verify(client, times(1)).verifyRepresentation(any(), any(), any());
    }

    /** Popis za izbornik: sortiran po nazivu (hrvatska abeceda), bez same osobe, iz sesije 5 minuta. */
    @Test
    void representedCompanies_sortedAndCachedInSession() {
        when(client.representedCompanies("sesija-1", PERSON)).thenReturn(List.of(
                new ZastupanaTvrtka("85821130368", "ZAGREBAČKA d.o.o."),
                new ZastupanaTvrtka(PERSON, "Ana Horvat"),
                new ZastupanaTvrtka(COMPANY, "ČAKOVEČKA d.o.o."),
                new ZastupanaTvrtka("39986540678", "ADRIATIQUE GROUP D.O.O.")));

        List<ZastupanaTvrtka> first = service.representedCompanies(session, IDENTITY);
        List<ZastupanaTvrtka> second = service.representedCompanies(session, IDENTITY);

        assertThat(first).extracting(ZastupanaTvrtka::naziv)
                .containsExactly("ADRIATIQUE GROUP D.O.O.", "ČAKOVEČKA d.o.o.", "ZAGREBAČKA d.o.o.");
        assertThat(second).isEqualTo(first);
        verify(client, times(1)).representedCompanies(any(), any());
    }

    /** Nakon 5 minuta, ili za drugu osobu u istoj sesiji, popis se dohvaća ponovo. */
    @Test
    void representedCompanies_refetchedAfterTtl_orForAnotherPerson() {
        when(client.representedCompanies(any(), any())).thenReturn(List.of(new ZastupanaTvrtka(COMPANY, "TVRTKA")));
        service.representedCompanies(session, IDENTITY);

        Clock later = Clock.fixed(Instant.parse("2026-09-29T10:06:00Z"), ZoneOffset.UTC);
        new ActingSubjectService(client, new ActingSubjectRateLimiter(100, 100, later), later)
                .representedCompanies(session, IDENTITY);
        service.representedCompanies(session, new NiasIdentity("55555555551", "Iva", "Ivić", "sesija-9", null));

        verify(client, times(3)).representedCompanies(any(), any());
    }

    /** „Osoba nije u e-Ovlaštenjima" je prazan popis, ne 403 — i pamti se kao uspjeh. */
    @Test
    void representedCompanies_notInEOvlastenja_isEmptyList() {
        when(client.representedCompanies(any(), any())).thenThrow(new EOvlastenjaException(
                EOvlastenjaException.Reason.NOT_REPRESENTATIVE, "400", "nije u e-Ovlaštenjima"));

        assertThat(service.representedCompanies(session, IDENTITY)).isEmpty();
        assertThat(service.representedCompanies(session, IDENTITY)).isEmpty();
        verify(client, times(1)).representedCompanies(any(), any());
    }

    /**
     * FINA ne prihvaća sjednicu: 503, ne 401 — popis frontend dohvaća sam, pa bi 401 pokrenuo
     * ponovnu prijavu. Neuspjeh se pamti 60 s: nema novog poziva pri svakom otvaranju izbornika.
     */
    @Test
    void representedCompanies_sessionRejected_isUnavailable_andCachedBriefly() {
        when(client.representedCompanies(any(), any())).thenThrow(new EOvlastenjaException(
                EOvlastenjaException.Reason.SESSION, "203", "istekla"));

        assertThatThrownBy(() -> service.representedCompanies(session, IDENTITY))
                .isInstanceOf(ActingSubjectOptionsUnavailableException.class);
        assertThatThrownBy(() -> service.representedCompanies(session, IDENTITY))
                .isInstanceOf(ActingSubjectOptionsUnavailableException.class);
        verify(client, times(1)).representedCompanies(any(), any());
    }

    @Test
    void representedCompanies_registryDown_isUnavailable_thenRetriedAfterAMinute() {
        when(client.representedCompanies(any(), any()))
                .thenThrow(new ExternalRegistryException("EOVLASTENJA", "e-Ovlaštenja odbijaju uslugu (šifra 100)"))
                .thenReturn(List.of(new ZastupanaTvrtka(COMPANY, "TVRTKA")));
        assertThatThrownBy(() -> service.representedCompanies(session, IDENTITY))
                .isInstanceOf(ActingSubjectOptionsUnavailableException.class);

        Clock later = Clock.fixed(Instant.parse("2026-09-29T10:01:01Z"), ZoneOffset.UTC);
        List<ZastupanaTvrtka> retried = new ActingSubjectService(client, new ActingSubjectRateLimiter(100, 100, later), later)
                .representedCompanies(session, IDENTITY);

        assertThat(retried).extracting(ZastupanaTvrtka::oib).containsExactly(COMPANY);
        verify(client, times(2)).representedCompanies(any(), any());
    }

    /** Keš ide u Spring Session JDBC, pa mora preživjeti serijalizaciju. */
    @Test
    void cachedOptions_areSerializable() throws Exception {
        ActingSubjectService.CachedOptions original = new ActingSubjectService.CachedOptions(PERSON,
                Instant.parse("2026-09-29T10:00:00Z"), List.of(new ZastupanaTvrtka(COMPANY, "TVRTKA")), false);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(original);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertThat(in.readObject()).isEqualTo(original);
        }
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

    /**
     * Nakon nove NIAS prijave ponovna potvrda koristi sjednicu iz <b>nove</b> prijave, a ne onu iz
     * vremena odabira — inače bi e-Ovlaštenja javila istekao {@code sesija_id} (EOVLASTENJA_SESSION)
     * i frontend bi korisnika vrtio na ponovnu prijavu.
     */
    @Test
    void afterNewLogin_reverificationUsesNewSession() {
        when(client.verifyRepresentation(any(), any(), any())).thenReturn(zastupanje());
        service.select(session, IDENTITY, COMPANY);                       // odabrano uz „sesija-1"
        when(client.verifyRepresentation(eq("sesija-1"), any(), any())).thenThrow(new EOvlastenjaException(
                EOvlastenjaException.Reason.SESSION, "203", "istekla"));

        Authentication newLogin = samlAuth("sesija-2");
        NiasOibResolver niasOibResolver = mock(NiasOibResolver.class);
        when(niasOibResolver.resolve(newLogin)).thenReturn(Optional.of(PERSON));
        EffectiveOibResolver resolver = new EffectiveOibResolver(niasOibResolver, service);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            assertThat(resolver.reverifiedActingSubject(newLogin)).map(ActingSubject::legalOib).contains(COMPANY);
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
