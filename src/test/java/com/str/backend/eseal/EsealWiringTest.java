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

    /** Servis na internoj adresi samo preko HTTP-a (CDU test): uz izričito uključenje starta. */
    @Test
    void plainHttpToInternalIp_withAllowHttp_starts() {
        runner.withPropertyValues("str.eseal.enabled=true", "str.eseal.base-url=http://10.20.30.40:8080",
                        "str.eseal.api-key=kljuc", "str.eseal.project=STR", "str.eseal.allow-http=true")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(EsealHttpClient.class));
    }

    @Test
    void plainHttpToInternalIp_withoutAllowHttp_namesTheSwitch() {
        runner.withPropertyValues("str.eseal.enabled=true", "str.eseal.base-url=http://10.20.30.40:8080",
                        "str.eseal.api-key=kljuc", "str.eseal.project=STR")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("10.20.30.40").hasMessageContaining("ESEAL_ALLOW_HTTP"));
    }

    /** Navodnici iz .env-a završe u shemi — poruka to mora reći, ne samo „mora biti https". */
    @Test
    void quotedBaseUrl_reportsTheScheme() {
        runner.withPropertyValues("str.eseal.enabled=true", "str.eseal.base-url=\"https://eseal.test\"",
                        "str.eseal.api-key=kljuc", "str.eseal.project=STR")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure().hasStackTraceContaining("navodnici"));
    }

    @Test
    void underscoreInHost_isReportedAsUnrecognisedHost() {
        runner.withPropertyValues("str.eseal.enabled=true", "str.eseal.base-url=https://str_eseal:8443",
                        "str.eseal.api-key=kljuc", "str.eseal.project=STR")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("host nije prepoznat"));
    }

    @Test
    void emptyEnabled_isDisabled() {
        runner.withPropertyValues("str.eseal.enabled=")
                .run(ctx -> assertThat(ctx.getBean(PdfSealer.class).isEnabled()).isFalse());
    }
}
