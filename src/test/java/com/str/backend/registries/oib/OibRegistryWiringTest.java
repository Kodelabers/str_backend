package com.str.backend.registries.oib;

import com.str.backend.lessor.LegalEntityRegistry;
import com.str.backend.lessor.SubjectRegistry;
import com.str.backend.lessor.UnavailableLegalEntityRegistry;
import com.str.backend.str.StrSubjectRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Dvije zastavice OIB sustava su neovisne: uključivanje toka za tvrtke ne smije promijeniti izvor
 * podataka fizičke osobe ({@link SubjectRegistry}), i obrnuto.
 */
class OibRegistryWiringTest {

    /** {@link StrSubjectRegistry} je uvijek bean: jedini izvor bez OIB sustava, zamjenski uz njega. */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(OibRegistryConfig.class, OibRegistryHttpClient.class,
                    OibSubjectRegistry.class, OibLegalEntityRegistry.class, UnavailableLegalEntityRegistry.class)
            .withBean(StrSubjectRegistry.class, () -> mock(StrSubjectRegistry.class));

    @Test
    void defaults_noClient_legalEntityDataUnavailable() {
        runner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(RestClient.class);
            assertThat(ctx.getBean(SubjectRegistry.class)).isInstanceOf(StrSubjectRegistry.class);
            assertThat(ctx.getBean(LegalEntityRegistry.class)).isInstanceOf(UnavailableLegalEntityRegistry.class);
        });
    }

    @Test
    void legalEntityEnabled_doesNotSwitchNaturalPersonToOibRegistry() {
        runner.withPropertyValues("app.oib-registry.legal-entity-enabled=true",
                        "app.oib-registry.base-url=https://oib.test/str-internal-api")
                .run(ctx -> {
                    assertThat(ctx.getBean(LegalEntityRegistry.class)).isInstanceOf(OibLegalEntityRegistry.class);
                    assertThat(ctx.getBean(SubjectRegistry.class)).isInstanceOf(StrSubjectRegistry.class);
                });
    }

    /** Uz dvije implementacije porta {@code SubjectProfileService} mora dobiti OIB sustav ({@code @Primary}). */
    @Test
    void naturalPersonEnabled_doesNotTurnOnLegalEntityFlow() {
        runner.withPropertyValues("app.oib-registry.enabled=true",
                        "app.oib-registry.base-url=https://oib.test/str-internal-api")
                .run(ctx -> {
                    assertThat(ctx.getBean(SubjectRegistry.class)).isInstanceOf(OibSubjectRegistry.class);
                    assertThat(ctx.getBean(LegalEntityRegistry.class)).isInstanceOf(UnavailableLegalEntityRegistry.class);
                });
    }

    /** Uz poziv ide korisnikova sesija, pa plain HTTP smije samo prema lokalnom stroju. */
    @Test
    void plainHttpBaseUrl_failsAtStartup() {
        runner.withPropertyValues("app.oib-registry.enabled=true",
                        "app.oib-registry.base-url=http://et2-test-internal-eturizam.gov.hr/str-internal-api")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void plainHttpToLocalhost_isAllowed() {
        runner.withPropertyValues("app.oib-registry.enabled=true",
                        "app.oib-registry.base-url=http://localhost:8089/str-internal-api")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    /** Prazna env varijabla (npr. {@code APP_OIB_REGISTRY_LEGAL_ENTITY_ENABLED=}) znači „isključeno", a ne pad starta. */
    @Test
    void emptyFlags_meanDisabled() {
        runner.withPropertyValues("app.oib-registry.enabled=", "app.oib-registry.legal-entity-enabled=")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(RestClient.class);
                    assertThat(ctx.getBean(LegalEntityRegistry.class)).isInstanceOf(UnavailableLegalEntityRegistry.class);
                });
    }

    @Test
    void legalEntityEnabled_withoutBaseUrl_failsAtStartup() {
        runner.withPropertyValues("app.oib-registry.legal-entity-enabled=true")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
