package com.str.backend.auth.nias;

import org.opensaml.saml.saml2.core.AuthnRequest;
import org.opensaml.saml.saml2.core.Conditions;
import org.opensaml.saml.saml2.core.LogoutRequest;
import org.opensaml.saml.saml2.core.NameID;
import org.opensaml.saml.saml2.core.NameIDPolicy;
import org.opensaml.saml.saml2.core.NameIDType;
import org.opensaml.saml.saml2.core.OneTimeUse;
import org.opensaml.saml.saml2.core.impl.ConditionsBuilder;
import org.opensaml.saml.saml2.core.impl.NameIDPolicyBuilder;
import org.opensaml.saml.saml2.core.impl.OneTimeUseBuilder;
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
import org.springframework.security.saml2.provider.service.registration.InMemoryRelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrations;
import org.springframework.security.saml2.provider.service.registration.Saml2MessageBinding;
import org.springframework.security.saml2.provider.service.web.DefaultRelyingPartyRegistrationResolver;
import org.springframework.security.saml2.provider.service.web.RelyingPartyRegistrationResolver;
import org.springframework.security.saml2.provider.service.web.authentication.OpenSaml4AuthenticationRequestResolver;
import org.springframework.security.saml2.provider.service.web.authentication.logout.OpenSaml4LogoutRequestResolver;
import org.springframework.security.saml2.provider.service.web.authentication.logout.Saml2RelyingPartyInitiatedLogoutSuccessHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;

import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "nias.saml.enabled", havingValue = "true")
@EnableConfigurationProperties(NiasSamlProperties.class)
public class NiasSamlConfig {

    static final String REGISTRATION_ID = "nias";

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
                .assertingPartyDetails(p -> p.singleSignOnServiceBinding(Saml2MessageBinding.REDIRECT))
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
    public LogoutSuccessHandler saml2LogoutSuccessHandler(RelyingPartyRegistrationResolver resolver) {
        OpenSaml4LogoutRequestResolver logoutRequestResolver = new OpenSaml4LogoutRequestResolver(resolver);
        logoutRequestResolver.setParametersConsumer(params -> {
            LogoutRequest req = params.getLogoutRequest();
            NameID nameId = req.getNameID();
            if (nameId != null) {
                nameId.setFormat(NameIDType.PERSISTENT);
            }
        });
        return new Saml2RelyingPartyInitiatedLogoutSuccessHandler(logoutRequestResolver);
    }
}
