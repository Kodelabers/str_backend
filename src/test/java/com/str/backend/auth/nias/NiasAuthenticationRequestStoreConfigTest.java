package com.str.backend.auth.nias;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.web.Saml2AuthenticationRequestRepository;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@code Saml2LoginConfigurer} repozitorij dohvaća s {@code getIfUnique()}: dva beana ili nijedan
 * znače tihi prelazak na Springov sesijski repozitorij. Za svaku vrijednost
 * {@code nias.saml.request-store} mora postojati točno jedan, i to pravi.
 */
class NiasAuthenticationRequestStoreConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(NiasAuthenticationRequestStoreConfig.class)
            .withBean(SamlAuthRequestRepository.class, () -> mock(SamlAuthRequestRepository.class))
            .withBean(RelyingPartyRegistrationRepository.class, () -> mock(RelyingPartyRegistrationRepository.class))
            .withBean(Clock.class, Clock::systemUTC)
            .withPropertyValues("nias.saml.enabled=true");

    @ParameterizedTest
    @ValueSource(strings = {"database", "DATABASE", "Database"})
    void database_isTheOnlyRepository(String value) {
        runner.withPropertyValues("nias.saml.request-store=" + value).run(ctx ->
                assertThat(ctx.getBeanProvider(Saml2AuthenticationRequestRepository.class).getIfUnique())
                        .isInstanceOf(DatabaseSaml2AuthenticationRequestRepository.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"session", "", "nesto-trece"})
    void otherwise_sessionIsTheOnlyRepository(String value) {
        runner.withPropertyValues("nias.saml.request-store=" + value).run(ctx ->
                assertThat(ctx.getBeanProvider(Saml2AuthenticationRequestRepository.class).getIfUnique())
                        .isInstanceOf(SessionReturnToAuthenticationRequestRepository.class));
    }

    @Test
    void unset_meansSession() {
        runner.run(ctx ->
                assertThat(ctx.getBeanProvider(Saml2AuthenticationRequestRepository.class).getIfUnique())
                        .isInstanceOf(SessionReturnToAuthenticationRequestRepository.class));
    }

    @Test
    void niasOff_noRepository() {
        runner.withPropertyValues("nias.saml.enabled=false").run(ctx ->
                assertThat(ctx).doesNotHaveBean(Saml2AuthenticationRequestRepository.class));
    }
}
