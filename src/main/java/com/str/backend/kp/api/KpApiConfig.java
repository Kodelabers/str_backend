package com.str.backend.kp.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

/** KP API (T16): klijent s HTTP Basic prijavom i pool za slanje izvan niti zahtjeva. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KpApiProperties.class)
public class KpApiConfig {

    private static final Logger log = LoggerFactory.getLogger(KpApiConfig.class);

    @Bean
    KpApiClient kpApiClient(KpApiProperties properties) {
        if (properties.enabled() && !properties.aktivno()) {
            log.warn("startup_kp_api enabled=true aktivno=false nedostaje=[{}] — akti županijskih službenika idu "
                    + "samo e-poštom dok se ne postavi", properties.nedostaje());
        } else {
            log.info("startup_kp_api enabled={} aktivno={} base_url={} korisnik={} lozinka={} tip_poruke={} po_aktu={}",
                    properties.enabled(), properties.aktivno(), properties.baseUrl(), properties.username(),
                    properties.password() != null ? "postavljena" : "nema", properties.tipPoruke(),
                    properties.tipPorukePoAktu());
        }
        if (properties.aktivno() && properties.baseUrl().startsWith("http://")) {
            log.warn("startup_kp_api_http base_url={} — lozinka HTTP Basic prijave ide nekriptirano; "
                    + "izvan lokalnog razvoja koristiti https", properties.baseUrl());
        }
        return new KpApiClient(configure(RestClient.builder(), properties).build());
    }

    /**
     * HTTP Basic prijava na svakom pozivu i bez slijeđenja preusmjerenja: odbijena prijava koja preusmjeri na
     * stranicu prijave ostaje 3xx, pa je klijent prepoznaje kao odbijenu prijavu, a ne kao nečitljiv HTML.
     * Bez adrese klijent se ne koristi ({@link KpApiProperties#aktivno}).
     */
    static RestClient.Builder configure(RestClient.Builder builder, KpApiProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout((int) properties.connectTimeout().toMillis());
        factory.setReadTimeout((int) properties.readTimeout().toMillis());
        builder.baseUrl(properties.baseUrl() != null ? properties.baseUrl() : "http://kp-api.nije-konfiguriran")
                .requestFactory(factory);
        if (properties.username() != null && properties.password() != null) {
            // UTF-8 kao Spring Security na strani KP-a; zadani ISO-8859-1 bi znakom izvan njega srušio start.
            builder.defaultHeaders(h -> h.setBasicAuth(properties.username(), properties.password(),
                    StandardCharsets.UTF_8));
        }
        return builder;
    }

    @Bean
    KpApiExecutor kpApiExecutor(KpApiProperties properties) {
        return new KpApiExecutor(properties);
    }
}
