package com.str.backend.eseal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.HttpURLConnection;

/**
 * Propertyji se registriraju bezuvjetno; {@link RestClient} samo uz {@code str.eseal.enabled=true},
 * kad nepotpuna konfiguracija mora pasti na startu, a ne na prvom aktu.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EsealProperties.class)
public class EsealConfig {

    private static final Logger log = LoggerFactory.getLogger(EsealConfig.class);

    @Bean
    @ConditionalOnProperty(name = "str.eseal.enabled", havingValue = "true")
    RestClient esealRestClient(EsealProperties properties) {
        properties.requireComplete();
        if (properties.isInsecureHttp()) {
            log.warn("eseal_insecure_http base={} — API ključ i akti idu bez TLS-a (str.eseal.allow-http=true);"
                    + " dopušteno samo na internoj mreži, za produkciju traži https:// adresu", properties.baseUrl());
        }
        // Ključ se nikad ne logira.
        log.info("eseal base={} project={} connect_timeout_ms={} read_timeout_ms={}",
                properties.baseUrl(), properties.project(),
                properties.effectiveConnectTimeoutMs(), properties.effectiveReadTimeoutMs());
        return configure(RestClient.builder(), properties).build();
    }

    /** Izdvojeno da test koristi isti builder. */
    static RestClient.Builder configure(RestClient.Builder builder, EsealProperties properties) {
        // Bez slijeđenja preusmjerenja: POST s aktom ne smije otići na adresu koju nismo konfigurirali,
        // a 3xx u logu jasnije govori da je base-url kriv nego HTML stranica na koju bi vodio.
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
        return builder
                .baseUrl(properties.baseUrl().trim())
                .requestFactory(factory)
                .defaultHeader("X-API-Key", properties.apiKey())
                .defaultHeader("Project", properties.project());
    }
}
