package com.str.backend.registries.oib;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.HttpURLConnection;

/**
 * Propertyji se registriraju bezuvjetno; {@link RestClient} samo kad je uključen bar jedan tok
 * ({@link AnyFlowEnabled}), kad nepotpuna konfiguracija mora pasti na startu, a ne na prvom
 * zahtjevu korisnika.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OibRegistryProperties.class)
public class OibRegistryConfig {

    private static final Logger log = LoggerFactory.getLogger(OibRegistryConfig.class);

    /**
     * Fizička osoba ili tvrtka — oba toka dijele isti klijent. Uključeno je samo {@code true}, kao
     * kod {@code @ConditionalOnProperty}: prazna env varijabla ili druga vrijednost znači „isključeno",
     * a ne nečitljiv uvjet koji ruši start.
     */
    static final class AnyFlowEnabled extends AnyNestedCondition {

        AnyFlowEnabled() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnProperty(name = "app.oib-registry.enabled", havingValue = "true")
        static final class NaturalPerson {
        }

        @ConditionalOnProperty(name = "app.oib-registry.legal-entity-enabled", havingValue = "true")
        static final class LegalEntity {
        }
    }

    @Bean
    @Conditional(AnyFlowEnabled.class)
    RestClient oibRegistryRestClient(OibRegistryProperties properties) {
        properties.requireComplete();
        log.info("oib_registry base={} person={} legal_entity={}",
                properties.baseUrl(), properties.enabled(), properties.legalEntityEnabled());
        // Bez slijeđenja preusmjerenja: servis je bio iza NIAS-a i anonimni poziv dobivao 302 na
        // /saml2/authenticate/nias. Slijeđen, takav bi 302 završio HTML stranicom prijave i u logu
        // bi pisalo „nečitljiv odgovor"; ovako piše status 302, tj. da poziv nije autoriziran.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod)
                    throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(properties.effectiveConnectTimeoutMs());
        factory.setReadTimeout(properties.effectiveReadTimeoutMs());
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
    }
}
