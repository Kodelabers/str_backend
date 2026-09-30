package com.str.backend.registries.eovlastenja;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.str.backend.exception.ExternalRegistryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Pozivi {@code GetAuthorizationUnionPermission} (provjera zastupanja) i {@code GetNavigationData}
 * (popis tvrtki) preko obostranog TLS-a. Transport (keystore, truststore) slaže
 * {@link EOvlastenjaConfig}; provjera odgovora je u {@link EOvlastenjaResponseParser}.
 *
 * <p>Tijela idu kao <b>bajtovi</b>, ne kao {@code String}: bez {@code charset} u {@code Content-Type}
 * Spring bi odgovor dekodirao kao ISO-8859-1, pa bi svaki naziv s č/ć/š/ž/đ promijenio potpisani
 * sadržaj i provjera potpisa bi pala. XML parser kodiranje čita iz same poruke.
 *
 * <p>U log idu samo ishod, trajanje i šifre — OIB-ovi i sjednica ne.
 */
public class EOvlastenjaHttpClient implements EOvlastenjaClient {

    private static final Logger log = LoggerFactory.getLogger(EOvlastenjaHttpClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern CODE = Pattern.compile("[0-9A-Za-z]{1,10}");

    private final RestClient restClient;
    private final EOvlastenjaResponseParser parser;
    private final String permissionUrl;
    private final String navigationUrl;

    EOvlastenjaHttpClient(RestClient restClient, EOvlastenjaResponseParser parser,
                          String permissionUrl, String navigationUrl) {
        this.restClient = restClient;
        this.parser = parser;
        this.permissionUrl = permissionUrl;
        this.navigationUrl = navigationUrl;
    }

    @Override
    public Zastupanje verifyRepresentation(String sesijaId, String personOib, String legalOib) {
        requireSesijaId(sesijaId);
        EOvlastenjaRequest request = build(() -> EOvlastenjaRequest.of(sesijaId, personOib, legalOib));
        long start = System.nanoTime();
        byte[] body = post("eovlastenja_call", permissionUrl, request, start);
        try {
            Zastupanje result = parser.parse(body, request.id(), personOib, legalOib);
            log.info("eovlastenja_call ok ms={} functions={}", elapsedMs(start), result.functions().size());
            return result;
        } catch (EOvlastenjaException e) {
            log.info("eovlastenja_call rejected ms={} reason={} code={}", elapsedMs(start), e.reason(), e.code());
            throw e;
        }
    }

    @Override
    public List<ZastupanaTvrtka> representedCompanies(String sesijaId, String personOib) {
        requireSesijaId(sesijaId);
        EOvlastenjaRequest request = build(() -> EOvlastenjaRequest.navigation(sesijaId, personOib));
        long start = System.nanoTime();
        byte[] body = post("eovlastenja_navigation", navigationUrl, request, start);
        try {
            List<ZastupanaTvrtka> companies = parser.parseNavigation(body, request.id(), personOib);
            log.info("eovlastenja_navigation ok ms={} companies={}", elapsedMs(start), companies.size());
            return companies;
        } catch (EOvlastenjaException e) {
            log.info("eovlastenja_navigation rejected ms={} reason={} code={}", elapsedMs(start), e.reason(), e.code());
            throw e;
        }
    }

    /**
     * NIAS šalje {@code sesija_id} samo usluzi registriranoj i za autorizaciju — to je stanje
     * registracije, ne korisnikove sjednice. Ponovna prijava ga ne donosi, pa bi 401 („prijavite se
     * ponovo") korisnika vrtio u krug na NIAS. Zato 503, bez poziva.
     */
    private static void requireSesijaId(String sesijaId) {
        if (sesijaId == null || sesijaId.isBlank()) {
            throw new ExternalRegistryException(EOvlastenjaResponseParser.REGISTRY,
                    "NIAS prijava ne nosi sesija_id — usluga nije registrirana za e-Ovlaštenja (v. nias_login attributes)");
        }
    }

    /** Npr. {@code sesija_id} neočekivanog formata — vrijednost se ne logira, samo koje polje. */
    private static EOvlastenjaRequest build(Supplier<EOvlastenjaRequest> factory) {
        try {
            return factory.get();
        } catch (IllegalArgumentException e) {
            throw new ExternalRegistryException(EOvlastenjaResponseParser.REGISTRY, e.getMessage(), e);
        }
    }

    private byte[] post(String operation, String url, EOvlastenjaRequest request, long start) {
        byte[] body;
        try {
            body = restClient.post()
                    .uri(url)
                    .contentType(new MediaType(MediaType.APPLICATION_XML, StandardCharsets.UTF_8))
                    .accept(MediaType.APPLICATION_XML)
                    .body(request.xml().getBytes(StandardCharsets.UTF_8))
                    .retrieve()
                    .body(byte[].class);
        } catch (RestClientResponseException e) {
            throw errorResponse(operation, e, start);
        } catch (RestClientException e) {
            // Jedan redak za grep: korijenski uzrok transporta — npr. SSLHandshakeException: bad_certificate.
            log.warn("{} failed ms={} error={} cause={}", operation, elapsedMs(start),
                    e.getClass().getSimpleName(), rootCause(e));
            throw new ExternalRegistryException(EOvlastenjaResponseParser.REGISTRY, "e-Ovlaštenja nisu dostupna", e);
        }
        if (body == null || body.length == 0) {
            throw new ExternalRegistryException(EOvlastenjaResponseParser.REGISTRY, "e-Ovlaštenja: prazan odgovor");
        }
        return body;
    }

    /**
     * HTTP 4xx/5xx. Grešku pristupa (npr. šifra 100) FINA vraća kao nepotpisan JSON
     * {@code {"Code":"100","Message":…}} uz HTTP 400, a ne u potpisanom XML-u (izmjereno na CDU-u
     * 29.09.2026.). Šifra se čita i mapira istom tablicom kao potpisane greške — samo na odbijanja,
     * pa nepotpisanost ne smeta ({@link EOvlastenjaErrorCodes}). Bez šifre: 503, a početak tijela
     * ide u log ({@code error_body}).
     */
    private static RuntimeException errorResponse(String operation, RestClientResponseException e, long start) {
        JsonNode json = readJson(e.getResponseBodyAsByteArray());
        // Nepotpisana vrijednost ide u log i poruku — samo kratka alfanumerička šifra (bez log forginga).
        String rawCode = field(json, "Code", "code");
        String code = rawCode != null && CODE.matcher(rawCode).matches() ? rawCode : null;
        String message = field(json, "Message", "message");
        int status = e.getStatusCode().value();
        log.warn("{} failed ms={} error={} status={} code={}", operation, elapsedMs(start),
                e.getClass().getSimpleName(), status, code != null ? code : "-");
        if (code == null) {
            // Bez prepoznate šifre (npr. validacijska poruka ili tekst umjesto JSON-a) ovo je jedini
            // trag uzroka: popis tvrtki uzrok ne ispisuje stack traceom. Skraćeno, bez kontrolnih
            // znakova i s maskiranim 11-znamenkastim brojevima (OIB-ovi).
            log.warn("{} error_body content_type={} body=\"{}\"", operation,
                    e.getResponseHeaders() != null ? e.getResponseHeaders().getContentType() : null,
                    bodyPreview(e.getResponseBodyAsByteArray()));
        }
        // Uz šifru bez izvorne iznimke: njezina poruka nosi tijelo s FINA-inim tekstom, koji može
        // navoditi subjekte. Bez šifre (HTML proxyja i sl.) tijelo ostaje u stack traceu za dijagnozu.
        return EOvlastenjaErrorCodes.rejection(code, message).orElseGet(() -> code != null
                ? new ExternalRegistryException(EOvlastenjaResponseParser.REGISTRY,
                        "e-Ovlaštenja odbijaju zahtjev (HTTP " + status + ", šifra " + code + ")")
                : new ExternalRegistryException(EOvlastenjaResponseParser.REGISTRY, "e-Ovlaštenja nisu dostupna", e));
    }

    /** Početak tijela greške za log: najviše 300 znakova, bez kontrolnih znakova, OIB-ovi i sesija_id maskirani. */
    static String bodyPreview(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        String text = new String(body, 0, Math.min(body.length, 600), StandardCharsets.UTF_8)
                .replaceAll("\\p{Cntrl}", " ")
                .replaceAll("\\d{11}", "<oib>")
                .replaceAll("[0-9A-Fa-f]{4}(-[0-9A-Fa-f]{4}){3,}", "<sesija>");
        return text.length() > 300 ? text.substring(0, 300) + "…" : text;
    }

    private static JsonNode readJson(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(body);
            return node != null && node.isObject() ? node : null;
        } catch (Exception notJson) {
            return null;
        }
    }

    private static String field(JsonNode json, String... names) {
        if (json == null) {
            return null;
        }
        for (String name : names) {
            JsonNode value = json.get(name);
            if (value != null && !value.isNull() && !value.asText().isBlank()) {
                return value.asText().trim();
            }
        }
        return null;
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
