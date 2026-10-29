package com.str.backend.eseal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Klijent STR eSeal servisa, E1 {@code POST /api/v1/seals/pdf}: šalje PDF, dobiva isti PDF s
 * kvalificiranim elektroničkim pečatom (PAdES).
 *
 * <p>Jedan poziv, bez vlastitog retryja: ponavljanje vodi eGOP tok ({@code EgopRetryJob}), koji ima
 * spremljeni ključ i spremljene bajtove. Servis ponovljeni zahtjev s istim {@code Idempotency-Key} i
 * istim tijelom ne pečatira ponovo, nego vraća spremljeni ishod ({@code 200 Idempotent-Replayed}).
 *
 * <p>Prihvaćeni odgovor mora biti PDF čiji SHA-256 odgovara {@code Repr-Digest} headeru i uz koji
 * idu {@code Document-Guid}, {@code Control-Number} i {@code Seal-Level}. Kontrolni broj je tajna (s
 * GUID-om otvara dokument na E2) — ne ide u log ni u poruke iznimaka.
 */
@Component
@ConditionalOnProperty(name = "str.eseal.enabled", havingValue = "true")
public class EsealHttpClient {

    static final String PATH = "/api/v1/seals/pdf";
    private static final Logger log = LoggerFactory.getLogger(EsealHttpClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final MediaType PROBLEM_JSON = MediaType.valueOf("application/problem+json");
    /** Kodovi servisa su velika slova i podvlake; ono što tako ne izgleda ne ide u log (log forging). */
    private static final Pattern ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern TRACE_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Pattern BUSINESS_REFERENCE_FORBIDDEN = Pattern.compile("[^A-Za-z0-9 ._/:#()-]");
    private static final int BUSINESS_REFERENCE_MAX = 128;
    private static final int ERROR_BODY_MAX = 16 * 1024;
    /** Pečaćeni akt je ulaz + potpis i vremenski žig; gornja granica čuva memoriju od pogrešnog odgovora. */
    static final int SEALED_BODY_MAX = 64 * 1024 * 1024;
    private static final Pattern CONTROL_NUMBER =
            Pattern.compile("[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}");
    private static final Set<String> SEAL_LEVELS = Set.of("B-B", "B-T", "B-LT", "B-LTA");
    private static final Pattern RETRY_AFTER = Pattern.compile("[A-Za-z0-9 ,:]{1,40}");

    /**
     * Pečaćeni akt i ono što servis o njemu vrati.
     *
     * @param replayed servis je vratio ishod ranijeg poziva s istim ključem ({@code 200})
     */
    public record Sealed(byte[] pdf, UUID documentGuid, String controlNumber, String sealLevel,
                         String digest, String serviceVersion, boolean replayed) {

        /** Bez kontrolnog broja i sadržaja akta — record bi ih inače ispisao u svaki log. */
        @Override
        public String toString() {
            return "Sealed[documentGuid=" + documentGuid + ", sealLevel=" + sealLevel + ", bytes=" + pdf.length
                    + ", replayed=" + replayed + "]";
        }
    }

    private final RestClient restClient;

    public EsealHttpClient(RestClient esealRestClient) {
        this.restClient = esealRestClient;
    }

    /**
     * @param idempotencyKey    16–128 printable ASCII; isti za svaki pokušaj istog akta
     * @param businessReference npr. URBROJ; čisti se na skup znakova koji servis prima, prazno se ne šalje
     * @throws EsealException kad akt nije pečatiran ili odgovor nije prihvatljiv
     */
    public Sealed seal(byte[] pdf, String idempotencyKey, String businessReference) {
        long start = System.nanoTime();
        String reference = businessReference(businessReference);
        try {
            return restClient.post()
                    .uri(PATH)
                    .contentType(MediaType.APPLICATION_PDF)
                    .accept(MediaType.APPLICATION_PDF, PROBLEM_JSON)
                    .header("Idempotency-Key", idempotencyKey)
                    // Isti ključ i kao korelacija: jedan identifikator povezuje naš i njihov log.
                    .header("X-Correlation-ID", idempotencyKey)
                    .headers(h -> {
                        if (reference != null) {
                            h.set("Business-Reference", reference);
                        }
                    })
                    // byte[], ne stream: servis traži Content-Length (411 bez njega).
                    .body(pdf)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 200 || status == 201) {
                            byte[] body = response.getBody().readNBytes(SEALED_BODY_MAX + 1);
                            return accept(status, response.getHeaders(), body, pdf, idempotencyKey, start);
                        }
                        throw rejection(status, response.getHeaders(), response.getBody(), idempotencyKey, start);
                    });
        } catch (RestClientException e) {
            // Timeout, mreža, TLS. Servis je možda pečatirao — retry istim ključem dobiva ishod.
            log.warn("eseal_seal ishod=transport_error key={} trajanje_ms={} error={} cause={}",
                    idempotencyKey, elapsedMs(start), e.getClass().getSimpleName(), rootCause(e));
            throw new EsealException(0, "TRANSPORT", null, true, rootCause(e), e);
        } catch (EsealException e) {
            throw e;
        } catch (RuntimeException e) {
            // Greška na našoj strani prije ili oko poziva (npr. neispravan header) — ne smije izaći
            // kao neki drugi tip i preskočiti eseal log.
            log.error("eseal_seal ishod=klijent_greska key={} error={}", idempotencyKey, rootCause(e), e);
            throw new EsealException(0, "CLIENT_ERROR", null, false, rootCause(e), e);
        }
    }

    private static Sealed accept(int status, HttpHeaders headers, byte[] body, byte[] input, String key,
                                 long start) {
        String guid = headers.getFirst("Document-Guid");
        String controlNumber = headers.getFirst("Control-Number");
        String sealLevel = headers.getFirst("Seal-Level");
        if (body.length > SEALED_BODY_MAX) {
            throw invalid(status, key, start, "RESPONSE_INVALID", false, "tijelo odgovora veće od " + SEALED_BODY_MAX);
        }
        if (body.length < 5 || !new String(body, 0, 5, StandardCharsets.US_ASCII).startsWith("%PDF")) {
            throw invalid(status, key, start, "RESPONSE_INVALID", false, "tijelo odgovora nije PDF");
        }
        if (Arrays.equals(body, input)) {
            // Proxy ili pogrešno podešen servis koji vrati ulaz s headerima ne smije proći kao pečat.
            throw invalid(status, key, start, "RESPONSE_INVALID", false, "odgovor je nepromijenjeni ulazni PDF");
        }
        UUID documentGuid;
        try {
            documentGuid = UUID.fromString(guid == null ? "" : guid.trim());
        } catch (IllegalArgumentException e) {
            throw invalid(status, key, start, "RESPONSE_INVALID", false, "nedostaje ili neispravan Document-Guid");
        }
        // Oblik se provjerava i zbog stupaca (seal_control_number 16, seal_level 8): vrijednost koja ne
        // stane srušila bi upis nakon uspjelog pečata, a replay bi ponavljao istu vrijednost zauvijek.
        if (controlNumber == null || !CONTROL_NUMBER.matcher(controlNumber.trim()).matches()) {
            throw invalid(status, key, start, "RESPONSE_INVALID", false, "nedostaje ili neispravan Control-Number");
        }
        if (sealLevel == null || !SEAL_LEVELS.contains(sealLevel.trim())) {
            throw invalid(status, key, start, "RESPONSE_INVALID", false, "nedostaje ili nepoznat Seal-Level");
        }
        String digest = headers.getFirst("Repr-Digest");
        String expected = sha256(digest);
        if (expected == null) {
            throw invalid(status, key, start, "DIGEST_MISSING", false, "nedostaje sha-256 u Repr-Digest");
        }
        String actual = Base64.getEncoder().encodeToString(sha256Bytes(body));
        if (!expected.equals(actual)) {
            // Oštećenje na putu; retry istim ključem dobiva spremljeni dokument.
            throw invalid(status, key, start, "DIGEST_MISMATCH", true, "SHA-256 tijela ne odgovara Repr-Digest");
        }
        boolean replayed = status == 200;
        log.info("eseal_seal ishod=ok status={} key={} document_guid={} seal_level={} replayed={}"
                        + " velicina_in={} velicina_out={} service_version={} trajanje_ms={}",
                status, key, documentGuid, sealLevel.trim(), replayed, input.length, body.length,
                headers.getFirst("Service-Version"), elapsedMs(start));
        return new Sealed(body, documentGuid, controlNumber.trim(), sealLevel.trim(), "sha-256=:" + expected + ":",
                headers.getFirst("Service-Version"), replayed);
    }

    private static EsealException invalid(int status, String key, long start, String code, boolean retryable,
                                          String detail) {
        log.warn("eseal_seal ishod=odbijen_odgovor status={} key={} error_code={} trajanje_ms={} — {}",
                status, key, code, elapsedMs(start), detail);
        return new EsealException(status, code, null, retryable, detail);
    }

    /**
     * 4xx/5xx. Servis vraća RFC 9457 tijelo s {@code errorCode}, {@code traceId} i {@code retryable};
     * proxy (502/504) vraća tijelo bez toga — tada je kod {@code HTTP_<status>}, a retryable po
     * pravilima specifikacije.
     */
    private static EsealException rejection(int status, HttpHeaders headers, InputStream body, String key,
                                            long start) {
        JsonNode problem = readProblem(headers, body);
        String rawCode = text(problem, "errorCode");
        String errorCode = rawCode != null && ERROR_CODE.matcher(rawCode).matches() ? rawCode : "HTTP_" + status;
        String rawTrace = text(problem, "traceId");
        String traceId = rawTrace != null && TRACE_ID.matcher(rawTrace).matches() ? rawTrace : null;
        boolean retryable = problem != null && problem.path("retryable").isBoolean()
                ? problem.path("retryable").asBoolean()
                : status == 429 || status == 502 || status == 503 || status == 504;
        String rawRetryAfter = headers.getFirst(HttpHeaders.RETRY_AFTER);
        String retryAfter = rawRetryAfter != null && RETRY_AFTER.matcher(rawRetryAfter.strip()).matches()
                ? rawRetryAfter.strip() : null;
        log.warn("eseal_seal ishod=greska status={} key={} error_code={} retryable={} trace_id={} retry_after={}"
                        + " trajanje_ms={}",
                status, key, errorCode, retryable, traceId == null ? "-" : traceId,
                retryAfter == null ? "-" : retryAfter, elapsedMs(start));
        return new EsealException(status, errorCode, traceId, retryable,
                retryAfter == null ? null : "Retry-After=" + retryAfter);
    }

    private static JsonNode readProblem(HttpHeaders headers, InputStream body) {
        MediaType type = headers.getContentType();
        if (type == null || !(type.isCompatibleWith(PROBLEM_JSON) || type.isCompatibleWith(MediaType.APPLICATION_JSON))) {
            return null;
        }
        try (InputStream in = body) {
            byte[] bytes = in.readNBytes(ERROR_BODY_MAX);
            JsonNode node = JSON.readTree(bytes);
            return node != null && node.isObject() ? node : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    /**
     * {@code sha-256=:<base64>:} iz RFC 9530 headera; header smije nositi više algoritama odvojenih
     * zarezom. {@code null} ako sha-256 nema.
     */
    static String sha256(String reprDigest) {
        if (reprDigest == null) {
            return null;
        }
        for (String member : reprDigest.split(",")) {
            String m = member.strip();
            int eq = m.indexOf('=');
            if (eq > 0 && m.substring(0, eq).strip().equalsIgnoreCase("sha-256")) {
                String value = m.substring(eq + 1).strip();
                if (value.length() > 2 && value.startsWith(":") && value.endsWith(":")) {
                    return value.substring(1, value.length() - 1);
                }
            }
        }
        return null;
    }

    /**
     * Servis prima {@code A-Z a-z 0-9 razmak . _ - / : # ( )}, 1–128 znakova, bez razmaka na rubovima.
     * Dijakritika se svodi na osnovno slovo, ostalo na crticu; prazno se ne šalje.
     */
    static String businessReference(String value) {
        if (value == null) {
            return null;
        }
        String ascii = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String cleaned = BUSINESS_REFERENCE_FORBIDDEN.matcher(ascii).replaceAll("-");
        if (cleaned.length() > BUSINESS_REFERENCE_MAX) {
            cleaned = cleaned.substring(0, BUSINESS_REFERENCE_MAX);
        }
        cleaned = cleaned.strip();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static byte[] sha256Bytes(byte[] body) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(body);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM bez SHA-256", e);
        }
    }

    private static String rootCause(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null ? "" : ": " + message.replaceAll("\\p{Cntrl}", " "));
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
