package com.str.backend.registries.oib;

import com.str.backend.lessor.LegalEntityRegistry;
import com.str.backend.lessor.SubjectRegistry;
import com.str.backend.lessor.UnavailableLegalEntityRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dvije zastavice OIB sustava su neovisne: uključivanje toka za tvrtke ne smije promijeniti izvor
 * podataka fizičke osobe ({@link SubjectRegistry}), i obrnuto.
 */
class OibRegistryWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(OibRegistryConfig.class, OibRegistryHttpClient.class,
                    OibSubjectRegistry.class, OibLegalEntityRegistry.class, UnavailableLegalEntityRegistry.class);

    @Test
    void defaults_noClient_legalEntityDataUnavailable() {
        runner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(RestClient.class);
            assertThat(ctx).doesNotHaveBean(SubjectRegistry.class);
            assertThat(ctx.getBean(LegalEntityRegistry.class)).isInstanceOf(UnavailableLegalEntityRegistry.class);
        });
    }

    @Test
    void legalEntityEnabled_doesNotSwitchNaturalPersonToOibRegistry() {
        runner.withPropertyValues("app.oib-registry.legal-entity-enabled=true",
                        "app.oib-registry.base-url=http://oib.test/str-internal-api")
                .run(ctx -> {
                    assertThat(ctx.getBean(LegalEntityRegistry.class)).isInstanceOf(OibLegalEntityRegistry.class);
                    assertThat(ctx).doesNotHaveBean(SubjectRegistry.class);
                });
    }

    @Test
    void naturalPersonEnabled_doesNotTurnOnLegalEntityFlow() {
        runner.withPropertyValues("app.oib-registry.enabled=true",
                        "app.oib-registry.base-url=http://oib.test/str-internal-api")
                .run(ctx -> {
                    assertThat(ctx.getBean(SubjectRegistry.class)).isInstanceOf(OibSubjectRegistry.class);
                    assertThat(ctx.getBean(LegalEntityRegistry.class)).isInstanceOf(UnavailableLegalEntityRegistry.class);
                });
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
