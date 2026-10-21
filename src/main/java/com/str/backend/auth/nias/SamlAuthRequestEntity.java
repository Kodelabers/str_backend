package com.str.backend.auth.nias;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Odlazni SAML AuthnRequest, spremljen izvan {@code HttpSessiona}.
 *
 * <p>Ključ je ID samog zahtjeva, jer se pri povratku traži po {@code InResponseTo} iz NIAS-ovog
 * odgovora. Vidi {@link DatabaseSaml2AuthenticationRequestRepository} i changeset 064.
 *
 * <p>Red je kratkotrajan: briše se čim se iskoristi, a neiskorišteni (korisnik odustane na
 * NIAS-u) čiste se po {@code createdAt}.
 */
@Entity
@Table(schema = "str_rn", name = "saml_auth_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SamlAuthRequestEntity {

    @Id
    @Column(name = "request_id", nullable = false, updatable = false, length = 200)
    private String requestId;

    @Column(name = "saml_request", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String samlRequest;

    @Column(name = "relay_state", updatable = false, columnDefinition = "TEXT")
    private String relayState;

    @Column(name = "authentication_request_uri", nullable = false, updatable = false, length = 1000)
    private String authenticationRequestUri;

    @Column(name = "relying_party_registration_id", updatable = false, length = 100)
    private String relyingPartyRegistrationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Put na FE-u kamo se korisnik vraća nakon prijave ({@link NiasReturnTo}); changeset 141. */
    @Column(name = "return_to", updatable = false, length = NiasReturnTo.MAX_LENGTH)
    private String returnTo;

    private SamlAuthRequestEntity(String requestId, String samlRequest, String relayState,
                                  String authenticationRequestUri, String relyingPartyRegistrationId,
                                  Instant createdAt, String returnTo) {
        this.requestId = requestId;
        this.samlRequest = samlRequest;
        this.relayState = relayState;
        this.authenticationRequestUri = authenticationRequestUri;
        this.relyingPartyRegistrationId = relyingPartyRegistrationId;
        this.createdAt = createdAt;
        this.returnTo = returnTo;
    }

    public static SamlAuthRequestEntity create(String requestId, String samlRequest, String relayState,
                                               String authenticationRequestUri,
                                               String relyingPartyRegistrationId, Instant createdAt,
                                               String returnTo) {
        return new SamlAuthRequestEntity(requestId, samlRequest, relayState, authenticationRequestUri,
                relyingPartyRegistrationId, createdAt, returnTo);
    }
}
