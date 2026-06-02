package com.str.backend.auth.nias;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.saml2.core.Saml2X509Credential;
import org.springframework.security.saml2.provider.service.registration.InMemoryRelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrations;

import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "nias.saml.enabled", havingValue = "true")
@EnableConfigurationProperties(NiasSamlProperties.class)
public class NiasSamlConfig {

    static final String REGISTRATION_ID = "nias";

    @Bean
    public RelyingPartyRegistrationRepository relyingPartyRegistrationRepository(NiasSamlProperties props) throws Exception {
        Saml2X509Credential signingCred = loadSigningCredential(props);

        RelyingPartyRegistration registration = RelyingPartyRegistrations
                .fromMetadataLocation(props.metadataUri())
                .registrationId(REGISTRATION_ID)
                .entityId(props.entityId())
                .assertionConsumerServiceLocation(props.acsUrl())
                .signingX509Credentials(creds -> creds.add(signingCred))
                .build();

        return new InMemoryRelyingPartyRegistrationRepository(registration);
    }

    private Saml2X509Credential loadSigningCredential(NiasSamlProperties props) throws Exception {
        KeyStore ks = KeyStore.getInstance("JKS");
        try (FileInputStream fis = new FileInputStream(props.keystorePath())) {
            ks.load(fis, props.keystorePassword().toCharArray());
        }
        PrivateKey privateKey = (PrivateKey) ks.getKey(props.keyAlias(), props.keystorePassword().toCharArray());
        X509Certificate cert = (X509Certificate) ks.getCertificate(props.keyAlias());
        return Saml2X509Credential.signing(privateKey, cert);
    }
}
