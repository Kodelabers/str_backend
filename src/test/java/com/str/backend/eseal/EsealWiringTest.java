package com.str.backend.eseal;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ugašen eSeal ne diže klijent, a {@link PdfSealer} postoji i kaže da je ugašen; upaljen s nepotpunom
 * konfiguracijom ruši start — bolje nego da prvi akt padne tek kad nastane.
 */
class EsealWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EsealConfig.class, EsealHttpClient.class, PdfSealer.class);

    @Test
    void defaults_noClient_sealerDisabled() {
        runner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(EsealHttpClient.class);
            assertThat(ctx.getBean(PdfSealer.class).isEnabled()).isFalse();
        });
    }

    @Test
    void enabledAndComplete_startsClient() {
        runner.withPropertyValues("str.eseal.enabled=true", "str.eseal.base-url=https://eseal.test",
                        "str.eseal.api-key=kljuc", "str.eseal.project=STR")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(EsealHttpClient.class);
                    assertThat(ctx.getBean(PdfSealer.class).isEnabled()).isTrue();
                });
    }

    @Test
    void enabledWithoutApiKey_failsAtStartup() {
        runner.withPropertyValues("str.eseal.enabled=true", "str.eseal.base-url=https://eseal.test",
                        "str.eseal.project=STR")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("str.eseal.api-key"));
    }

    /** Uz poziv idu API ključ i akt s osobnim podacima, pa plain HTTP smije samo prema lokalnom stroju. */
    @Test
    void plainHttpBaseUrl_failsAtStartup() {
        runner.withPropertyValues("str.eseal.enabled=true", "str.eseal.base-url=http://eseal.test",
                        "str.eseal.api-key=kljuc", "str.eseal.project=STR")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    /** Vrijednost koju Spring veže kao true, a uvjet beana ne prihvaća: pečatiranje ostaje ugašeno, ne pola-upaljeno. */
    @Test
    void enabledYes_isConsistentlyDisabled() {
        runner.withPropertyValues("str.eseal.enabled=yes")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(EsealHttpClient.class);
                    assertThat(ctx.getBean(PdfSealer.class).isEnabled()).isFalse();
                });
    }

    @Test
    void emptyEnabled_isDisabled() {
        runner.withPropertyValues("str.eseal.enabled=")
                .run(ctx -> assertThat(ctx.getBean(PdfSealer.class).isEnabled()).isFalse());
    }
}
