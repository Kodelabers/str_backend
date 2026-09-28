package com.str.backend.registries.oib;

import com.fasterxml.jackson.databind.JsonNode;
import com.str.backend.exception.ExternalRegistryException;
import com.str.backend.lessor.RegistrySubject;
import com.str.backend.lessor.SubjectRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;

/**
 * HTTP klijent OIB sustava. Diže se samo uz {@code app.oib-registry.enabled=true}; inače su
 * podaci iz {@code str.StrSubjectRegistry}.
 *
 * <p>Ishodi: 200 s podacima o osobi → subjekt; 404, prazno tijelo ili odgovor bez podataka i bez
 * grešaka → „registar ga ne poznaje"; 3xx/401/403 → poziv bez prijave (servis je iza NIAS-a);
 * sve ostalo (5xx, timeout, nečitljiv odgovor, greške bez podataka — v.
 * {@link OibRegistryResponseMapper}) → {@link ExternalRegistryException}, tj. 503. U log ide samo
 * status i trajanje — OIB i podaci o osobi ne.
 */
@Component
@ConditionalOnProperty(name = "app.oib-registry.enabled", havingValue = "true")
public class OibRegistryHttpClient implements SubjectRegistry {

    static final String REGISTRY = "OIB";
    private static final String PATH = "/pretraga-registra/oib";
    private static final Logger log = LoggerFactory.getLogger(OibRegistryHttpClient.class);

    private final RestClient restClient;

    public OibRegistryHttpClient(RestClient oibRegistryRestClient) {
        this.restClient = oibRegistryRestClient;
    }

    @Override
    public Optional<RegistrySubject> findByOib(String oib) {
        long start = System.nanoTime();
        try {
            return restClient.get()
                    .uri(uri -> uri.path(PATH).queryParam("oib", oib).build())
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        log.info("oib_registry_lookup status={} ms={}", status, elapsedMs(start));
                        if (status == HttpStatus.NOT_FOUND.value()) {
                            return Optional.empty();
                        }
                        if (response.getStatusCode().is3xxRedirection()
                                || status == HttpStatus.UNAUTHORIZED.value()
                                || status == HttpStatus.FORBIDDEN.value()) {
                            throw new ExternalRegistryException(REGISTRY,
                                    "OIB sustav odbio poziv bez prijave (status " + status + ")");
                        }
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new ExternalRegistryException(REGISTRY, "OIB sustav vratio status " + status);
                        }
                        JsonNode body = response.bodyTo(JsonNode.class);
                        if (body == null || body.isNull() || body.isEmpty()) {
                            return Optional.empty();
                        }
                        return OibRegistryResponseMapper.toSubject(oib, body);
                    });
        } catch (RestClientException e) {
            log.warn("oib_registry_lookup failed ms={} error={}", elapsedMs(start), e.getClass().getSimpleName());
            throw new ExternalRegistryException(REGISTRY, "OIB sustav nije dostupan", e);
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
