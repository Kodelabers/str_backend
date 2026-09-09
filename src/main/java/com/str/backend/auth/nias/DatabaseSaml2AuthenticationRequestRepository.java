package com.str.backend.auth.nias;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.saml2.provider.service.authentication.AbstractSaml2AuthenticationRequest;
import org.springframework.security.saml2.provider.service.authentication.Saml2PostAuthenticationRequest;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.web.Saml2AuthenticationRequestRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Sprema odlazni AuthnRequest u {@code str_rn.saml_auth_request} umjesto u {@code HttpSession}.
 *
 * <p><b>Zašto postoji.</b> Spring po defaultu koristi
 * {@code HttpSessionSaml2AuthenticationRequestRepository}, pa pri povratku s NIAS-a mora stići
 * sesijski cookie. NIAS na ACS šalje <b>cross-site POST</b>, a na takvom POST-u preglednik ne
 * šalje {@code SameSite=Lax} cookie (default kad atribut nije postavljen). {@code SameSite=None}
 * bi pomogao, ali zahtijeva {@code Secure}, koji preko plain HTTP-a otpada — dakle na okolini bez
 * HTTPS-a ne postoji kombinacija postavki koja to rješava. Simptom je bio
 * {@code invalid_in_response_to}: assertion stigne, ali spremljeni zahtjev se ne nađe.
 *
 * <p><b>Što se NE mijenja.</b> Korelacija zahtjev↔odgovor ostaje: zahtjev se pamti po svom ID-u i
 * traži po {@code InResponseTo} iz odgovora, pa zaštita od replaya vrijedi kao i prije. Premješta
 * se samo nosač, iz sesije u bazu. Usput to rješava i rad iza više instanci, gdje sesija u
 * memoriji ionako ne bi valjala.
 *
 * <p>Aktivira se s {@code nias.saml.request-store=database}; default je {@code session}.
 */
public class DatabaseSaml2AuthenticationRequestRepository
        implements Saml2AuthenticationRequestRepository<AbstractSaml2AuthenticationRequest> {

    private static final Logger log = LoggerFactory.getLogger(DatabaseSaml2AuthenticationRequestRepository.class);

    /** Koliko dugo neiskorišten zahtjev ostaje upotrebljiv. Prijava na NIAS-u traje kraće od ovoga. */
    static final Duration TTL = Duration.ofMinutes(15);

    private final SamlAuthRequestRepository repository;
    private final RelyingPartyRegistrationRepository registrations;
    private final Clock clock;

    public DatabaseSaml2AuthenticationRequestRepository(SamlAuthRequestRepository repository,
                                                        RelyingPartyRegistrationRepository registrations,
                                                        Clock clock) {
        this.repository = repository;
        this.registrations = registrations;
        this.clock = clock;
    }

    @Override
    public void saveAuthenticationRequest(AbstractSaml2AuthenticationRequest authenticationRequest,
                                          HttpServletRequest request, HttpServletResponse response) {
        if (authenticationRequest == null) {
            // Spring ovim putem i BRIŠE zahtjev (save(null)) — bez ove grane bi pao na NPE.
            removeAuthenticationRequest(request, response);
            return;
        }
        Instant now = clock.instant();
        repository.save(SamlAuthRequestEntity.create(
                authenticationRequest.getId(),
                authenticationRequest.getSamlRequest(),
                authenticationRequest.getRelayState(),
                authenticationRequest.getAuthenticationRequestUri(),
                authenticationRequest.getRelyingPartyRegistrationId(),
                now));

        // Čišćenje se vozi ovdje umjesto zasebnim @Scheduled poslom: tablica raste samo kad netko
        // krene u prijavu, pa je to točan trenutak za brisanje zaostataka i nema posla koji se
        // vrti uprazno.
        int obrisano = repository.deleteOlderThan(now.minus(TTL));
        if (obrisano > 0) {
            log.debug("saml_auth_request_cleanup obrisano={}", obrisano);
        }
        log.debug("saml_auth_request_saved id={}", authenticationRequest.getId());
    }

    @Override
    public AbstractSaml2AuthenticationRequest loadAuthenticationRequest(HttpServletRequest request) {
        return find(request).map(this::toSpring).orElse(null);
    }

    @Override
    public AbstractSaml2AuthenticationRequest removeAuthenticationRequest(HttpServletRequest request,
                                                                          HttpServletResponse response) {
        Optional<SamlAuthRequestEntity> found = find(request);
        found.ifPresent(e -> repository.deleteById(e.getRequestId()));
        return found.map(this::toSpring).orElse(null);
    }

    private Optional<SamlAuthRequestEntity> find(HttpServletRequest request) {
        String inResponseTo = NiasSecurityUtil.extractInResponseTo(request.getParameter("SAMLResponse"));
        if (inResponseTo == null) {
            return Optional.empty();
        }
        Optional<SamlAuthRequestEntity> found = repository.findById(inResponseTo);
        if (found.isEmpty()) {
            log.warn("saml_auth_request_not_found inResponseTo={} — zahtjev je istekao (TTL {}), "
                    + "već iskorišten, ili odgovor pripada drugoj instanci", inResponseTo, TTL);
            return Optional.empty();
        }
        // TTL se provjerava i pri čitanju: čišćenje se okida tek na sljedećoj prijavi, pa bi bez
        // ovoga prastari zahtjev mogao proći.
        if (found.get().getCreatedAt().isBefore(clock.instant().minus(TTL))) {
            log.warn("saml_auth_request_expired inResponseTo={} createdAt={}",
                    inResponseTo, found.get().getCreatedAt());
            repository.deleteById(found.get().getRequestId());
            return Optional.empty();
        }
        return found;
    }

    /**
     * Builder registraciju traži kao objekt (iz nje čita registrationId), pa se ne može predati
     * null — zato se dohvaća iz repozitorija po spremljenom id-u.
     */
    private AbstractSaml2AuthenticationRequest toSpring(SamlAuthRequestEntity e) {
        String registrationId = e.getRelyingPartyRegistrationId() != null
                ? e.getRelyingPartyRegistrationId()
                : NiasSamlConfig.REGISTRATION_ID;
        RelyingPartyRegistration registration = registrations.findByRegistrationId(registrationId);
        if (registration == null) {
            log.warn("saml_auth_request_unknown_registration id={} — zahtjev se ne može rekonstruirati",
                    registrationId);
            return null;
        }
        return Saml2PostAuthenticationRequest.withRelyingPartyRegistration(registration)
                .samlRequest(e.getSamlRequest())
                .relayState(e.getRelayState())
                .authenticationRequestUri(e.getAuthenticationRequestUri())
                .id(e.getRequestId())
                .build();
    }
}
