package com.str.backend.auth.nias;

import org.opensaml.saml.saml2.core.AuthnRequest;
import org.opensaml.saml.saml2.core.Conditions;
import org.opensaml.saml.saml2.core.LogoutRequest;
import org.opensaml.saml.saml2.core.NameID;
import org.opensaml.saml.saml2.core.NameIDPolicy;
import org.opensaml.saml.saml2.core.NameIDType;
import org.opensaml.saml.saml2.core.OneTimeUse;
import org.opensaml.saml.saml2.core.SessionIndex;
import org.opensaml.saml.saml2.core.impl.ConditionsBuilder;
import org.opensaml.saml.saml2.core.impl.NameIDPolicyBuilder;
import org.opensaml.saml.saml2.core.impl.OneTimeUseBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.saml2.core.Saml2X509Credential;
import org.springframework.security.saml2.core.Saml2X509Credential.Saml2X509CredentialType;
import org.springframework.security.saml2.provider.service.authentication.DefaultSaml2AuthenticatedPrincipal;
import org.springframework.security.saml2.provider.service.authentication.Saml2Authentication;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal;
import org.springframework.security.saml2.provider.service.authentication.logout.Saml2LogoutRequest;
import org.springframework.security.saml2.provider.service.registration.InMemoryRelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrations;
import org.springframework.security.saml2.provider.service.registration.Saml2MessageBinding;
import org.springframework.security.saml2.provider.service.web.DefaultRelyingPartyRegistrationResolver;
import org.springframework.security.saml2.provider.service.web.RelyingPartyRegistrationResolver;
import org.springframework.security.saml2.provider.service.web.authentication.OpenSaml4AuthenticationRequestResolver;
import org.springframework.security.saml2.provider.service.web.authentication.logout.OpenSaml4LogoutRequestResolver;
import org.springframework.security.saml2.provider.service.web.authentication.logout.Saml2LogoutRequestResolver;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.zip.Inflater;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "nias.saml.enabled", havingValue = "true")
@EnableConfigurationProperties(NiasSamlProperties.class)
public class NiasSamlConfig {

    static final String REGISTRATION_ID = "nias";

    private static final Logger log = LoggerFactory.getLogger(NiasSamlConfig.class);

    @Bean
    public Saml2X509Credential spSigningCredential(NiasSamlProperties props) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(props.keystorePath())) {
            ks.load(fis, props.keystorePassword().toCharArray());
        }
        PrivateKey privateKey = (PrivateKey) ks.getKey(props.keyAlias(), props.keystorePassword().toCharArray());
        X509Certificate cert = (X509Certificate) ks.getCertificate(props.keyAlias());
        return new Saml2X509Credential(
                privateKey, cert,
                Saml2X509CredentialType.SIGNING,
                Saml2X509CredentialType.DECRYPTION);
    }

    @Bean
    public RelyingPartyRegistrationRepository relyingPartyRegistrationRepository(
            Saml2X509Credential spSigningCredential, NiasSamlProperties props) {

        RelyingPartyRegistration registration = RelyingPartyRegistrations
                .fromMetadataLocation(props.metadataUri())
                .registrationId(REGISTRATION_ID)
                .entityId(props.entityId())
                .assertionConsumerServiceLocation(props.acsUrl())
                .singleLogoutServiceLocation(props.sloUrl())
                .signingX509Credentials(c -> c.add(spSigningCredential))
                .decryptionX509Credentials(c -> c.add(spSigningCredential))
                .assertingPartyDetails(p -> p.singleSignOnServiceBinding(Saml2MessageBinding.POST))
                .build();

        return new InMemoryRelyingPartyRegistrationRepository(registration);
    }

    @Bean
    public RelyingPartyRegistrationResolver relyingPartyRegistrationResolver(
            RelyingPartyRegistrationRepository registrations) {
        return new DefaultRelyingPartyRegistrationResolver(registrations);
    }

    @Bean
    public OpenSaml4AuthenticationRequestResolver authenticationRequestResolver(
            RelyingPartyRegistrationResolver resolver) {
        OpenSaml4AuthenticationRequestResolver requestResolver = new OpenSaml4AuthenticationRequestResolver(resolver);
        requestResolver.setAuthnRequestCustomizer(ctx -> {
            AuthnRequest req = ctx.getAuthnRequest();
            NameIDPolicy nameIDPolicy = new NameIDPolicyBuilder().buildObject();
            nameIDPolicy.setFormat(NameIDType.PERSISTENT);
            nameIDPolicy.setAllowCreate(true);
            req.setNameIDPolicy(nameIDPolicy);
            Conditions conditions = new ConditionsBuilder().buildObject();
            Instant now = Instant.now();
            conditions.setNotBefore(now);
            conditions.setNotOnOrAfter(now.plus(5, ChronoUnit.MINUTES));
            OneTimeUse oneTimeUse = new OneTimeUseBuilder().buildObject();
            conditions.getConditions().add(oneTimeUse);
            req.setConditions(conditions);
        });
        return requestResolver;
    }

    @Bean
    public AuthenticationSuccessHandler authenticationSuccessHandler(
            NiasSamlProperties props, NiasSessionRegistry sessionRegistry) {
        return (request, response, authentication) -> {
            if (authentication instanceof Saml2Authentication) {
                Saml2Authentication saml2Auth = (Saml2Authentication) authentication;
                String samlResponse = saml2Auth.getSaml2Response();
                List<String> sessionIndexes = NiasSecurityUtil.extractSessionIndexes(samlResponse);
                Saml2AuthenticatedPrincipal currentPrincipal = (Saml2AuthenticatedPrincipal) saml2Auth.getPrincipal();
                DefaultSaml2AuthenticatedPrincipal newPrincipal = new DefaultSaml2AuthenticatedPrincipal(
                        currentPrincipal.getName(),
                        currentPrincipal.getAttributes(),
                        sessionIndexes);
                newPrincipal.setRelyingPartyRegistrationId(currentPrincipal.getRelyingPartyRegistrationId());
                Saml2Authentication enriched = new Saml2Authentication(
                        newPrincipal, samlResponse, saml2Auth.getAuthorities());
                enriched.setDetails(saml2Auth.getDetails());
                SecurityContextHolder.getContext().setAuthentication(enriched);

                for (String sessionIndex : sessionIndexes) {
                    sessionRegistry.register(currentPrincipal.getName(), sessionIndex, request.getSession());
                }
            }
            response.sendRedirect(props.successRedirectUrl());
        };
    }

    @Bean
    public AuthenticationFailureHandler niasAuthenticationFailureHandler(NiasSamlProperties props) {
        return (request, response, exception) -> {
            jakarta.servlet.http.HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            response.sendRedirect(props.failureRedirectUrl());
        };
    }

    /**
     * Resolver za odlazni SP-initiated LogoutRequest. Mora biti ožičen u {@code .saml2Logout()}
     * (a NE samo u {@code .logout()}), jer fronta gađa saml2Logout URL — inače se koristi defaultni
     * resolver i customizacija (NameID PERSISTENT) se ne primijeni.
     */
    @Bean
    public Saml2LogoutRequestResolver niasLogoutRequestResolver(RelyingPartyRegistrationResolver resolver) {
        OpenSaml4LogoutRequestResolver delegate = new OpenSaml4LogoutRequestResolver(resolver);
        delegate.setParametersConsumer(params -> {
            LogoutRequest req = params.getLogoutRequest();

            // Spec §5.3 — Spring po defaultu NE postavlja ova tri; NIAS ih specifikacijom traži.
            // Issuer/@Format: spec izričito kaže "NIAS dopušta samo sljedeći format".
            if (req.getIssuer() != null) {
                req.getIssuer().setFormat(NameIDType.X509_SUBJECT);
            }
            // Reason: razlog odjave — korisnik je zatražio odjavu.
            req.setReason(LogoutRequest.USER_REASON);
            // NotOnOrAfter: vrijeme nakon kojeg poruka ne vrijedi (+5 min, kao u spec primjeru).
            Instant issued = req.getIssueInstant() != null ? req.getIssueInstant() : Instant.now();
            req.setNotOnOrAfter(issued.plus(5, ChronoUnit.MINUTES));

            // Spec §6.2 — NameID Format mora biti isti kao pri prijavi (persistent).
            NameID nameId = req.getNameID();
            if (nameId != null) {
                nameId.setFormat(NameIDType.PERSISTENT);
            }

            // TODO(SLO-debug): PRIVREMENO — sažetak polja. Ukloniti nakon dijagnostike.
            log.info("NIAS SLO LogoutRequest: nameIdPresent={}, nameIdFormat={}, issuerFormat={}, reason={}, notOnOrAfter={}, sessionIndexes={}",
                    nameId != null,
                    nameId != null ? nameId.getFormat() : null,
                    req.getIssuer() != null ? req.getIssuer().getFormat() : null,
                    req.getReason(),
                    req.getNotOnOrAfter(),
                    req.getSessionIndexes().stream().map(SessionIndex::getValue).toList());
        });

        // TODO(SLO-debug): PRIVREMENI wrapper — loga FINALNI (već potpisani) LogoutRequest XML,
        // točno onakav kakav odlazi NIAS-u, radi usporedbe sa spec §5.3. Namjerno se NE marshala
        // unutar parametersConsumera: keširanje DOM-a prije potpisivanja moglo bi razbiti potpis.
        // Ukloniti nakon verifikacije.
        return (request, authentication) -> {
            Saml2LogoutRequest logoutRequest = delegate.resolve(request, authentication);
            if (logoutRequest != null) {
                log.info("NIAS SLO LogoutRequest [binding={}, destination={}] XML:\n{}",
                        logoutRequest.getBinding(),
                        logoutRequest.getLocation(),
                        decodeSamlRequest(logoutRequest));
            }
            return logoutRequest;
        };
    }

    /** TODO(SLO-debug): PRIVREMENO — dekodira odlazni SAMLRequest u čitljiv XML. Ukloniti nakon verifikacije. */
    private static String decodeSamlRequest(Saml2LogoutRequest logoutRequest) {
        try {
            byte[] decoded = Base64.getDecoder().decode(logoutRequest.getSamlRequest());
            if (logoutRequest.getBinding() == Saml2MessageBinding.REDIRECT) {
                Inflater inflater = new Inflater(true);
                inflater.setInput(decoded);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[2048];
                while (!inflater.finished()) {
                    int n = inflater.inflate(buf);
                    if (n == 0) {
                        break;
                    }
                    out.write(buf, 0, n);
                }
                inflater.end();
                return out.toString(StandardCharsets.UTF_8);
            }
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "[decode error: " + e + "]";
        }
    }
}
