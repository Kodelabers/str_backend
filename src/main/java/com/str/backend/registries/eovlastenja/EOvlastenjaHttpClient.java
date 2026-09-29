package com.str.backend.registries.eovlastenja;

import com.str.backend.exception.ExternalRegistryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;

/**
 * Poziv {@code GetAuthorizationUnionPermission} preko obostranog TLS-a. Transport (keystore,
 * truststore) slaže {@link EOvlastenjaConfig}; provjera odgovora je u
 * {@link EOvlastenjaResponseParser}.
 *
 * <p>Tijela idu kao <b>bajtovi</b>, ne kao {@code String}: bez {@code charset} u {@code Content-Type}
 * Spring bi odgovor dekodirao kao ISO-8859-1, pa bi svaki naziv s č/ć/š/ž/đ promijenio potpisani
 * sadržaj i provjera potpisa bi pala. XML parser kodiranje čita iz same poruke.
 *
 * <p>U log idu samo ishod i trajanje — OIB-ovi i sjednica ne.
 */
public class EOvlastenjaHttpClient implements EOvlastenjaClient {

    private static final Logger log = LoggerFactory.getLogger(EOvlastenjaHttpClient.class);

    private final RestClient restClient;
    private final EOvlastenjaResponseParser parser;

    EOvlastenjaHttpClient(RestClient restClient, EOvlastenjaResponseParser parser) {
        this.restClient = restClient;
        this.parser = parser;
    }

    @Override
    public Zastupanje verifyRepresentation(String sesijaId, String personOib, String legalOib) {
        if (sesijaId == null || sesijaId.isBlank()) {
            // NIAS šalje sesija_id samo usluzi registriranoj i za autorizaciju — to je stanje
            // registracije, ne korisnikove sjednice. Ponovna prijava ga ne donosi, pa bi 401
            // („prijavite se ponovo") korisnika vrtio u krug na NIAS. Zato 503, bez poziva.
            throw new ExternalRegistryException(EOvlastenjaResponseParser.REGISTRY,
                    "NIAS prijava ne nosi sesija_id — usluga nije registrirana za e-Ovlaštenja (v. nias_login attributes)");
        }
        EOvlastenjaRequest request;
        try {
            request = EOvlastenjaRequest.of(sesijaId, personOib, legalOib);
        } catch (IllegalArgumentException e) {
            // Npr. sesija_id neočekivanog formata — vrijednost se ne logira, samo koje polje.
            throw new ExternalRegistryException(EOvlastenjaResponseParser.REGISTRY, e.getMessage(), e);
        }
        long start = System.nanoTime();
        byte[] body;
        try {
            body = restClient.post()
                    .contentType(new MediaType(MediaType.APPLICATION_XML, StandardCharsets.UTF_8))
                    .accept(MediaType.APPLICATION_XML)
                    .body(request.xml().getBytes(StandardCharsets.UTF_8))
                    .retrieve()
                    .body(byte[].class);
        } catch (RestClientException e) {
            // Jedan redak za grep: HTTP status (tijelo je u stack traceu iz GlobalExceptionHandlera)
            // ili korijenski uzrok transporta — npr. SSLHandshakeException: bad_certificate.
            log.warn("eovlastenja_call failed ms={} error={} {}", elapsedMs(start), e.getClass().getSimpleName(),
                    e instanceof RestClientResponseException r ? "status=" + r.getStatusCode().value() : "cause=" + rootCause(e));
            throw new ExternalRegistryException(EOvlastenjaResponseParser.REGISTRY, "e-Ovlaštenja nisu dostupna", e);
        }
        if (body == null || body.length == 0) {
            throw new ExternalRegistryException(EOvlastenjaResponseParser.REGISTRY, "e-Ovlaštenja: prazan odgovor");
        }
        try {
            Zastupanje result = parser.parse(body, request.id(), personOib, legalOib);
            log.info("eovlastenja_call ok ms={} functions={}", elapsedMs(start), result.functions().size());
            return result;
        } catch (EOvlastenjaException e) {
            log.info("eovlastenja_call rejected ms={} reason={} code={}", elapsedMs(start), e.reason(), e.code());
            throw e;
        }
    }

    private static String rootCause(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
