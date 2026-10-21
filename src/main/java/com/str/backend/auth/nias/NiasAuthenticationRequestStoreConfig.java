package com.str.backend.auth.nias;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.saml2.provider.service.authentication.AbstractSaml2AuthenticationRequest;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.web.Saml2AuthenticationRequestRepository;

import java.time.Clock;

/**
 * Gdje se čuva odlazni AuthnRequest do povratka s NIAS-a ({@code nias.saml.request-store}).
 *
 * <p>Uvijek postoji <b>točno jedan</b> bean: {@code Saml2LoginConfigurer} ga dohvaća s
 * {@code getIfUnique()}, pa bi uz dva tiho uzeo Springov sesijski repozitorij — na preprodu bi
 * prijava pala s {@code invalid_in_response_to}, a {@code ?returnTo=} bi se izgubio. Zato su uvjeti
 * komplementarni i zato su ovi beanovi izdvojeni iz {@link NiasSamlConfig}: da ih wiring test može
 * podići bez keystorea.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "nias.saml.enabled", havingValue = "true")
public class NiasAuthenticationRequestStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(NiasAuthenticationRequestStoreConfig.class);

    /**
     * Samo uz {@code nias.saml.request-store=database}: okolina bez HTTPS-a, gdje sesijski cookie
     * ne preživi NIAS-ov cross-site POST na ACS.
     */
    @Bean
    @ConditionalOnProperty(name = "nias.saml.request-store", havingValue = "database")
    public Saml2AuthenticationRequestRepository<AbstractSaml2AuthenticationRequest> niasAuthenticationRequestRepository(
            SamlAuthRequestRepository store, RelyingPartyRegistrationRepository registrations, Clock clock) {
        log.info("NIAS AuthnRequest se sprema u bazu (str_rn.saml_auth_request), ne u HttpSession — "
                + "sesijski cookie nije potreban na povratku s NIAS-a");
        return new DatabaseSaml2AuthenticationRequestRepository(store, registrations, clock);
    }

    /**
     * Komplement gornjeg beana: bez {@code database} AuthnRequest ostaje u sesiji kao i dosad, samo
     * uz zapamćeni {@code ?returnTo=} ({@link NiasReturnTo}).
     */
    @Bean
    @ConditionalOnExpression("!'${nias.saml.request-store:}'.equalsIgnoreCase('database')")
    public Saml2AuthenticationRequestRepository<AbstractSaml2AuthenticationRequest> niasSessionAuthenticationRequestRepository() {
        return new SessionReturnToAuthenticationRequestRepository();
    }
}
