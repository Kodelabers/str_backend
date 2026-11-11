package com.str.backend.kp.api;

import com.str.backend.auth.internal.CountyScopeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.time.Clock;

/** KP API (T16): klijent, pool za slanje i slanje zaostalih poruka na internom API-ju. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KpApiProperties.class)
public class KpApiConfig {

    private static final Logger log = LoggerFactory.getLogger(KpApiConfig.class);

    /** Interni API službenika — tu se šalju zaostale poruke. */
    static final String[] INTERNI_API = {"/api/rn/**", "/api/admin/**"};

    @Bean
    KpApiClient kpApiClient(KpApiProperties properties) {
        if (properties.enabled() && !properties.aktivno()) {
            log.warn("startup_kp_api enabled=true aktivno=false base_url={} tip_poruke={} — nedostaje adresa ili "
                    + "šifra poruke (STR_KP_API_BASE_URL, STR_KP_API_TIP_PORUKE); akti idu samo e-poštom",
                    properties.baseUrl() != null, properties.tipPoruke());
        } else {
            log.info("startup_kp_api enabled={} base_url={} tip_poruke={} po_aktu={}", properties.enabled(),
                    properties.baseUrl(), properties.tipPoruke(), properties.tipPorukePoAktu());
        }
        return new KpApiClient(configure(RestClient.builder(), properties).build());
    }

    /**
     * Bez slijeđenja preusmjerenja: sesija koju servis ne prepozna dobije 302 na NIAS prijavu, što klijent
     * prepoznaje kao neprihvaćenu sesiju. Bez adrese klijent se ne koristi ({@link KpApiProperties#aktivno}).
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
        return builder
                .baseUrl(properties.baseUrl() != null ? properties.baseUrl() : "http://kp-api.nije-konfiguriran")
                .requestFactory(factory);
    }

    @Bean
    KpApiExecutor kpApiExecutor(KpApiProperties properties) {
        return new KpApiExecutor(properties);
    }

    @Bean
    WebMvcConfigurer kpZaostaleWebConfig(KpPorukaService service, KpPorukaRepository repository,
                                         CountyScopeService countyScope, KpApiProperties properties, Clock clock) {
        KpZaostaleInterceptor interceptor = new KpZaostaleInterceptor(service, repository, countyScope, properties,
                clock);
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor).addPathPatterns(INTERNI_API);
            }
        };
    }
}
