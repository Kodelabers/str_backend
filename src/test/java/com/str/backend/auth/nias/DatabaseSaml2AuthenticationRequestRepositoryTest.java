package com.str.backend.auth.nias;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.saml2.provider.service.authentication.AbstractSaml2AuthenticationRequest;
import org.springframework.security.saml2.provider.service.authentication.Saml2PostAuthenticationRequest;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Poanta ovog repozitorija je da prijava prođe BEZ sesijskog cookieja, pa testovi nigdje ne
 * postavljaju sesiju — zahtjev se mora naći isključivo po {@code InResponseTo} iz odgovora.
 */
class DatabaseSaml2AuthenticationRequestRepositoryTest {

    private static final String REQ_ID = "ARQ599eb07-91fa-4001-bc88-8d3f83ba3c47";
    private static final Instant NOW = Instant.parse("2026-09-09T17:00:00Z");

    private InMemoryStore store;
    private DatabaseSaml2AuthenticationRequestRepository repo;
    private Clock clock;

    @BeforeEach
    void setUp() {
        store = new InMemoryStore();
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        RelyingPartyRegistration registration = RelyingPartyRegistration
                .withRegistrationId("nias")
                .entityId("CN=Test")
                .assertionConsumerServiceLocation("http://localhost:8086/login/saml2/sso/nias")
                .assertingPartyDetails(p -> p
                        .entityId("https://nias.gov.hr")
                        .singleSignOnServiceLocation("https://nias.gov.hr/sso-http"))
                .build();
        RelyingPartyRegistrationRepository registrations = id -> "nias".equals(id) ? registration : null;
        repo = new DatabaseSaml2AuthenticationRequestRepository(store, registrations, clock);
    }

    private static String samlResponse(String inResponseTo) {
        String xml = "<samlp:Response xmlns:samlp=\"urn:oasis:names:tc:SAML:2.0:protocol\""
                + (inResponseTo == null ? "" : " InResponseTo=\"" + inResponseTo + "\"")
                + " ID=\"_resp1\"/>";
        return Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8));
    }

    private static MockHttpServletRequest acsRequest(String inResponseTo) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login/saml2/sso/nias");
        request.addParameter("SAMLResponse", samlResponse(inResponseTo));
        return request;
    }

    private AbstractSaml2AuthenticationRequest saved() {
        return Saml2PostAuthenticationRequest
                .withRelyingPartyRegistration(RelyingPartyRegistration.withRegistrationId("nias")
                        .entityId("CN=Test")
                        .assertionConsumerServiceLocation("http://localhost:8086/login/saml2/sso/nias")
                        .assertingPartyDetails(p -> p
                                .entityId("https://nias.gov.hr")
                                .singleSignOnServiceLocation("https://nias.gov.hr/sso-http"))
                        .build())
                .samlRequest("PHNhbWxwOkF1dGhuUmVxdWVzdC8+")
                .relayState("state-1")
                .authenticationRequestUri("https://nias.gov.hr/sso-http")
                .id(REQ_ID)
                .build();
    }

    @Test
    @DisplayName("zahtjev se nađe po InResponseTo, bez ikakve sesije")
    void loads_byInResponseTo_withoutSession() {
        repo.saveAuthenticationRequest(saved(), new MockHttpServletRequest(), new MockHttpServletResponse());

        MockHttpServletRequest acs = acsRequest(REQ_ID);
        assertThat(acs.getSession(false)).isNull();   // upravo to je poanta

        AbstractSaml2AuthenticationRequest loaded = repo.loadAuthenticationRequest(acs);

        assertThat(loaded).isNotNull();
        assertThat(loaded.getId()).isEqualTo(REQ_ID);
        assertThat(loaded.getRelayState()).isEqualTo("state-1");
        assertThat(loaded.getAuthenticationRequestUri()).isEqualTo("https://nias.gov.hr/sso-http");
    }

    @Test
    @DisplayName("remove vrati zahtjev i obriše ga — drugi pokušaj je prazan (bez replaya)")
    void remove_consumesRequest() {
        repo.saveAuthenticationRequest(saved(), new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(repo.removeAuthenticationRequest(acsRequest(REQ_ID), new MockHttpServletResponse())).isNotNull();
        assertThat(store.rows).isEmpty();
        assertThat(repo.removeAuthenticationRequest(acsRequest(REQ_ID), new MockHttpServletResponse())).isNull();
    }

    @Test
    @DisplayName("tuđi InResponseTo ne vraća ništa")
    void returnsNull_forUnknownInResponseTo() {
        repo.saveAuthenticationRequest(saved(), new MockHttpServletRequest(), new MockHttpServletResponse());
        assertThat(repo.loadAuthenticationRequest(acsRequest("ARQ-netko-drugi"))).isNull();
    }

    @Test
    @DisplayName("odgovor bez InResponseTo (IdP-initiated) ne ruši ništa")
    void returnsNull_whenResponseHasNoInResponseTo() {
        repo.saveAuthenticationRequest(saved(), new MockHttpServletRequest(), new MockHttpServletResponse());
        assertThat(repo.loadAuthenticationRequest(acsRequest(null))).isNull();
    }

    @Test
    @DisplayName("neispravan SAMLResponse ne baca iznimku")
    void returnsNull_onGarbagePayload() {
        MockHttpServletRequest acs = new MockHttpServletRequest("POST", "/login/saml2/sso/nias");
        acs.addParameter("SAMLResponse", "ovo-nije-base64-xml");
        assertThat(repo.loadAuthenticationRequest(acs)).isNull();
        assertThat(repo.loadAuthenticationRequest(new MockHttpServletRequest())).isNull();
    }

    @Test
    @DisplayName("zahtjev stariji od TTL-a se odbija i briše")
    void expiredRequest_isRejected() {
        repo.saveAuthenticationRequest(saved(), new MockHttpServletRequest(), new MockHttpServletResponse());

        Clock kasnije = Clock.fixed(NOW.plus(DatabaseSaml2AuthenticationRequestRepository.TTL)
                .plus(Duration.ofSeconds(1)), ZoneOffset.UTC);
        var repoKasnije = new DatabaseSaml2AuthenticationRequestRepository(store, id -> null, kasnije);

        assertThat(repoKasnije.loadAuthenticationRequest(acsRequest(REQ_ID))).isNull();
        assertThat(store.rows).isEmpty();
    }

    @Test
    @DisplayName("save(null) je Springov način brisanja — ne smije puknuti na NPE")
    void save_null_removesInstead() {
        repo.saveAuthenticationRequest(saved(), new MockHttpServletRequest(), new MockHttpServletResponse());
        repo.saveAuthenticationRequest(null, acsRequest(REQ_ID), new MockHttpServletResponse());
        assertThat(store.rows).isEmpty();
    }

    /** Minimalni in-memory stub; JPA sloj nije predmet ovog testa. */
    private static final class InMemoryStore implements SamlAuthRequestRepository {
        private final Map<String, SamlAuthRequestEntity> rows = new HashMap<>();

        @Override
        public int deleteOlderThan(Instant threshold) {
            int prije = rows.size();
            rows.values().removeIf(e -> e.getCreatedAt().isBefore(threshold));
            return prije - rows.size();
        }

        @Override
        public <S extends SamlAuthRequestEntity> S save(S entity) {
            rows.put(entity.getRequestId(), entity);
            return entity;
        }

        @Override
        public Optional<SamlAuthRequestEntity> findById(String id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public void deleteById(String id) {
            rows.remove(id);
        }

        @Override public boolean existsById(String id) { return rows.containsKey(id); }
        @Override public long count() { return rows.size(); }
        @Override public void delete(SamlAuthRequestEntity e) { rows.remove(e.getRequestId()); }
        @Override public void deleteAll() { rows.clear(); }

        // --- ostatak JpaRepository sučelja nije potreban za ove testove ---
        @Override public java.util.List<SamlAuthRequestEntity> findAll() { throw nije(); }
        @Override public java.util.List<SamlAuthRequestEntity> findAll(org.springframework.data.domain.Sort s) { throw nije(); }
        @Override public java.util.List<SamlAuthRequestEntity> findAllById(Iterable<String> ids) { throw nije(); }
        @Override public <S extends SamlAuthRequestEntity> java.util.List<S> saveAll(Iterable<S> e) { throw nije(); }
        @Override public void flush() { throw nije(); }
        @Override public <S extends SamlAuthRequestEntity> S saveAndFlush(S e) { throw nije(); }
        @Override public <S extends SamlAuthRequestEntity> java.util.List<S> saveAllAndFlush(Iterable<S> e) { throw nije(); }
        @Override public void deleteAllInBatch(Iterable<SamlAuthRequestEntity> e) { throw nije(); }
        @Override public void deleteAllByIdInBatch(Iterable<String> ids) { throw nije(); }
        @Override public void deleteAllInBatch() { throw nije(); }
        @Override public SamlAuthRequestEntity getOne(String id) { throw nije(); }
        @Override public SamlAuthRequestEntity getById(String id) { throw nije(); }
        @Override public SamlAuthRequestEntity getReferenceById(String id) { throw nije(); }
        @Override public void deleteAllById(Iterable<? extends String> ids) { throw nije(); }
        @Override public void deleteAll(Iterable<? extends SamlAuthRequestEntity> e) { throw nije(); }
        @Override public org.springframework.data.domain.Page<SamlAuthRequestEntity> findAll(org.springframework.data.domain.Pageable p) { throw nije(); }
        @Override public <S extends SamlAuthRequestEntity> Optional<S> findOne(org.springframework.data.domain.Example<S> ex) { throw nije(); }
        @Override public <S extends SamlAuthRequestEntity> java.util.List<S> findAll(org.springframework.data.domain.Example<S> ex) { throw nije(); }
        @Override public <S extends SamlAuthRequestEntity> java.util.List<S> findAll(org.springframework.data.domain.Example<S> ex, org.springframework.data.domain.Sort s) { throw nije(); }
        @Override public <S extends SamlAuthRequestEntity> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> ex, org.springframework.data.domain.Pageable p) { throw nije(); }
        @Override public <S extends SamlAuthRequestEntity> long count(org.springframework.data.domain.Example<S> ex) { throw nije(); }
        @Override public <S extends SamlAuthRequestEntity> boolean exists(org.springframework.data.domain.Example<S> ex) { throw nije(); }
        @Override public <S extends SamlAuthRequestEntity, R> R findBy(org.springframework.data.domain.Example<S> ex,
                java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> fn) { throw nije(); }

        private static UnsupportedOperationException nije() {
            return new UnsupportedOperationException("test stub");
        }
    }
}
