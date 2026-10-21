package com.str.backend.registries.oib;

import com.fasterxml.jackson.databind.JsonNode;
import com.str.backend.exception.ExternalRegistryException;
import com.str.backend.lessor.RegistryLegalEntity;
import com.str.backend.lessor.RegistrySubject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/**
 * HTTP klijent OIB sustava: {@code GET /pretraga-registra/oib/FO/{oib}} za fizičku i
 * {@code /PO/{oib}} za pravnu osobu. Diže se kad je uključen bar jedan tok
 * ({@link OibRegistryConfig.AnyFlowEnabled}); koji ga tok koristi odlučuju adapteri
 * {@link OibSubjectRegistry} i {@link OibLegalEntityRegistry}.
 *
 * <p>Ono što nije OIB (11 znamenki) ne šalje se: OIB zastupnika dolazi iz eTurizma
 * ({@code subject_version.pin}), gdje može biti i strani identifikator — takav se tretira kao
 * „registar ga ne poznaje", bez poziva.
 *
 * <p>Raniji oblik {@code /pretraga-registra/oib?oib=…} više ne postoji: servis na njega vraća 200
 * s HTML-om svoje web aplikacije (izmjereno 08.10.2026.).
 *
 * <p>Ishodi: 200 s podacima → subjekt; 404, prazno tijelo ili odgovor bez podataka i bez grešaka →
 * „registar ga ne poznaje"; 3xx/401/403 → poziv bez prijave; sve ostalo (5xx, timeout, nečitljiv
 * odgovor, greške bez podataka — v. {@link OibRegistryResponseMapper}) →
 * {@link ExternalRegistryException}, tj. 503. U log ide samo vrsta, status i trajanje — OIB i podaci
 * o osobi ne. Iznimka je tijelo odgovora s greškom (osim 404 i 3xx/401/403), skraćeno i s maskiranim
 * OIB-ovima: to je jedini trag uzroka koji vidimo s naše strane.
 */
@Component
@Conditional(OibRegistryConfig.AnyFlowEnabled.class)
public class OibRegistryHttpClient {

    static final String REGISTRY = "OIB";
    private static final String PATH = "/pretraga-registra/oib/{kind}/{oib}";
    private static final Pattern OIB = Pattern.compile("\\d{11}");
    private static final Pattern OIB_ANYWHERE = Pattern.compile("(?<!\\d)\\d{11}(?!\\d)");
    static final int ERROR_BODY_MAX = 500;
    private static final Logger log = LoggerFactory.getLogger(OibRegistryHttpClient.class);

    private final RestClient restClient;

    public OibRegistryHttpClient(RestClient oibRegistryRestClient) {
        this.restClient = oibRegistryRestClient;
    }

    public Optional<RegistrySubject> findPerson(String oib) {
        return lookup("FO", oib, OibRegistryResponseMapper::toSubject);
    }

    public Optional<RegistryLegalEntity> findLegalEntity(String oib) {
        return lookup("PO", oib, OibRegistryResponseMapper::toLegalEntity);
    }

    private <T> Optional<T> lookup(String kind, String oib, BiFunction<String, JsonNode, Optional<T>> mapper) {
        if (oib == null || !OIB.matcher(oib).matches()) {
            log.info("oib_registry_lookup skipped kind={} reason=not_oib", kind);
            return Optional.empty();
        }
        long start = System.nanoTime();
        try {
            return restClient.get()
                    .uri(PATH, kind, oib)
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        log.info("oib_registry_lookup kind={} status={} ms={}", kind, status, elapsedMs(start));
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
                            // Bez tijela se iz našeg loga ne vidi ZAŠTO je servis pao (njegov log nam
                            // nije dostupan). OIB-ovi se maskiraju, tijelo se skraćuje.
                            log.warn("oib_registry_error kind={} status={} content_type={} body={}", kind, status,
                                    response.getHeaders().getContentType(), errorBody(response.getBody()));
                            throw new ExternalRegistryException(REGISTRY, "OIB sustav vratio status " + status);
                        }
                        JsonNode body = response.bodyTo(JsonNode.class);
                        if (body == null || body.isNull() || body.isEmpty()) {
                            return Optional.empty();
                        }
                        return mapper.apply(oib, body);
                    });
        } catch (RestClientException e) {
            log.warn("oib_registry_lookup failed kind={} ms={} error={}",
                    kind, elapsedMs(start), e.getClass().getSimpleName());
            throw new ExternalRegistryException(REGISTRY, "OIB sustav nije dostupan", e);
        }
    }

    /** Prvih {@value #ERROR_BODY_MAX} znakova, u jednom retku, s maskiranim OIB-ovima. */
    static String errorBody(InputStream body) {
        try (InputStream in = body) {
            byte[] bytes = in.readNBytes(ERROR_BODY_MAX * 4);
            String text = new String(bytes, StandardCharsets.UTF_8).replaceAll("\\s+", " ").strip();
            text = OIB_ANYWHERE.matcher(text).replaceAll("{oib}");
            return text.length() > ERROR_BODY_MAX ? text.substring(0, ERROR_BODY_MAX) + "…" : text;
        } catch (IOException | RuntimeException e) {
            return "<nečitljivo: " + e.getClass().getSimpleName() + ">";
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
