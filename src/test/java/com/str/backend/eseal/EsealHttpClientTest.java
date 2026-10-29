package com.str.backend.eseal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.DefaultResponseCreator;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * Ishodi E1 poziva. Prihvaća se samo PDF čiji SHA-256 odgovara {@code Repr-Digest}; svaka greška
 * nosi kod servisa, {@code traceId} i {@code retryable}, jer se iz toga na CDU-u čita zašto puca.
 */
@ExtendWith(OutputCaptureExtension.class)
class EsealHttpClientTest {

    private static final String URL = "https://eseal.test/api/v1/seals/pdf";
    private static final String KEY = "0b8f6a7e-2f1c-4c55-9d1e-7a3b2c1d0e9f";
    private static final String GUID = "3f2b8c1e-0d4a-4e57-9a61-2c7d5b9e8f10";
    private static final String CONTROL_NUMBER = "7K3M-Q9RT-X2VW";
    private static final byte[] PDF = "%PDF-1.4 akt".getBytes();
    private static final byte[] SEALED = "%PDF-1.4 akt + pecat".getBytes();

    private MockRestServiceServer server;
    private EsealHttpClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = EsealConfig.configure(RestClient.builder(),
                new EsealProperties(true, "https://eseal.test", "tajni-kljuc", "STR", null, null));
        server = MockRestServiceServer.bindTo(builder).build();
        client = new EsealHttpClient(builder.build());
    }

    @Test
    void sendsPdfWithKeyProjectAndIdempotencyHeaders() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-API-Key", "tajni-kljuc"))
                .andExpect(header("Project", "STR"))
                .andExpect(header("Idempotency-Key", KEY))
                .andExpect(header("X-Correlation-ID", KEY))
                .andExpect(header("Business-Reference", "529-06-03/01-26-TUREGBROJ-2"))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE))
                .andExpect(content().bytes(PDF))
                .andRespond(sealed(HttpStatus.CREATED, SEALED, digest(SEALED)));

        EsealHttpClient.Sealed result = client.seal(PDF, KEY, "529-06-03/01-26-TUREGBROJ-2");

        assertThat(result.pdf()).isEqualTo(SEALED);
        assertThat(result.documentGuid()).isEqualTo(UUID.fromString(GUID));
        assertThat(result.controlNumber()).isEqualTo(CONTROL_NUMBER);
        assertThat(result.sealLevel()).isEqualTo("B-LT");
        assertThat(result.digest()).isEqualTo("sha-256=:" + digest(SEALED) + ":");
        assertThat(result.replayed()).isFalse();
        server.verify();
    }

    /** 200 je ishod ranijeg poziva s istim ključem — jednako valjan pečat kao 201. */
    @Test
    void replayedResponse_isAccepted() {
        server.expect(requestTo(URL)).andRespond(sealed(HttpStatus.OK, SEALED, digest(SEALED)));

        assertThat(client.seal(PDF, KEY, null).replayed()).isTrue();
    }

    @Test
    void emptyBusinessReference_isNotSent() {
        server.expect(requestTo(URL)).andExpect(headerDoesNotExist("Business-Reference"))
                .andRespond(sealed(HttpStatus.CREATED, SEALED, digest(SEALED)));

        client.seal(PDF, KEY, "   ");
        server.verify();
    }

    @Test
    void digestMismatch_isRejectedAsRetryable() {
        server.expect(requestTo(URL)).andRespond(sealed(HttpStatus.CREATED, SEALED, digest(PDF)));

        assertThatThrownBy(() -> client.seal(PDF, KEY, null))
                .isInstanceOfSatisfying(EsealException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo("DIGEST_MISMATCH");
                    assertThat(e.retryable()).isTrue();
                });
    }

    @Test
    void responseWithoutPdfBody_isRejected() {
        byte[] html = "<html>login</html>".getBytes();
        server.expect(requestTo(URL)).andRespond(sealed(HttpStatus.CREATED, html, digest(html)));

        assertThatThrownBy(() -> client.seal(PDF, KEY, null))
                .isInstanceOfSatisfying(EsealException.class,
                        e -> assertThat(e.errorCode()).isEqualTo("RESPONSE_INVALID"));
    }

    /** Proxy ili pogrešno podešen servis koji vrati ulaz s headerima ne smije proći kao pečat. */
    @Test
    void unchangedInputReturned_isRejected() {
        server.expect(requestTo(URL)).andRespond(sealed(HttpStatus.CREATED, PDF, digest(PDF)));

        assertThatThrownBy(() -> client.seal(PDF, KEY, null))
                .isInstanceOfSatisfying(EsealException.class,
                        e -> assertThat(e.errorCode()).isEqualTo("RESPONSE_INVALID"));
    }

    /**
     * Neispravan kontrolni broj ili razina ne smiju proći kao pečat: ne bi stali u stupce, a upis bi
     * pao tek nakon pečatiranja — i ponavljao se sa svakim replayem.
     */
    @Test
    void malformedControlNumber_isRejected() {
        server.expect(requestTo(URL)).andRespond(response(HttpStatus.CREATED, SEALED, digest(SEALED),
                "nije-kontrolni-broj-uopce", "B-LT"));

        assertThatThrownBy(() -> client.seal(PDF, KEY, null))
                .isInstanceOfSatisfying(EsealException.class,
                        e -> assertThat(e.errorCode()).isEqualTo("RESPONSE_INVALID"));
    }

    @Test
    void missingSealLevel_isRejected() {
        server.expect(requestTo(URL)).andRespond(response(HttpStatus.CREATED, SEALED, digest(SEALED),
                CONTROL_NUMBER, null));

        assertThatThrownBy(() -> client.seal(PDF, KEY, null))
                .isInstanceOfSatisfying(EsealException.class,
                        e -> assertThat(e.errorCode()).isEqualTo("RESPONSE_INVALID"));
    }

    @Test
    void problemDetails_carryErrorCodeTraceAndRetryable() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(MediaType.valueOf("application/problem+json"))
                .body("""
                        {"type":"about:blank","title":"Unprocessable Entity","status":422,
                         "errorCode":"PDF_NOT_PDFA","traceId":"4bf92f3577b34da6a3ce929d0e0e4736","retryable":false}
                        """));

        assertThatThrownBy(() -> client.seal(PDF, KEY, null))
                .isInstanceOfSatisfying(EsealException.class, e -> {
                    assertThat(e.httpStatus()).isEqualTo(422);
                    assertThat(e.errorCode()).isEqualTo("PDF_NOT_PDFA");
                    assertThat(e.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
                    assertThat(e.retryable()).isFalse();
                    assertThat(e.getMessage()).contains("PDF_NOT_PDFA", "4bf92f3577b34da6a3ce929d0e0e4736");
                });
    }

    @Test
    void overloaded_isRetryableAndKeepsRetryAfter() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.valueOf("application/problem+json"))
                .header(HttpHeaders.RETRY_AFTER, "7")
                .body("{\"status\":503,\"errorCode\":\"OVERLOADED\",\"traceId\":\"t1\",\"retryable\":true}"));

        assertThatThrownBy(() -> client.seal(PDF, KEY, null))
                .isInstanceOfSatisfying(EsealException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo("OVERLOADED");
                    assertThat(e.retryable()).isTrue();
                    assertThat(e.getMessage()).contains("Retry-After=7");
                });
    }

    @Test
    void unexpectedRetryAfter_isNotCopiedIntoMessage() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, "7; <script>alert(1)</script>"));

        assertThatThrownBy(() -> client.seal(PDF, KEY, null))
                .isInstanceOfSatisfying(EsealException.class, e -> {
                    assertThat(e.retryable()).isTrue();
                    assertThat(e.getMessage()).doesNotContain("script");
                });
    }

    /** 502/504 proxyja nemaju problem+json tijelo; po specifikaciji su retryable. */
    @Test
    void proxyGatewayTimeout_withoutProblemBody_isRetryable() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.GATEWAY_TIMEOUT)
                .contentType(MediaType.TEXT_HTML).body("<html>504</html>"));

        assertThatThrownBy(() -> client.seal(PDF, KEY, null))
                .isInstanceOfSatisfying(EsealException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo("HTTP_504");
                    assertThat(e.retryable()).isTrue();
                });
    }

    @Test
    void unauthorized_withoutProblemBody_isNotRetryable() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.seal(PDF, KEY, null))
                .isInstanceOfSatisfying(EsealException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo("HTTP_401");
                    assertThat(e.retryable()).isFalse();
                });
    }

    @Test
    void timeout_isTransportAndRetryable() {
        server.expect(requestTo(URL)).andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> client.seal(PDF, KEY, null))
                .isInstanceOfSatisfying(EsealException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo("TRANSPORT");
                    assertThat(e.retryable()).isTrue();
                    assertThat(e.getMessage()).contains("SocketTimeoutException");
                });
    }

    /** Kontrolni broj s GUID-om otvara akt na eSeal servisu — ne smije u log. */
    @Test
    void controlNumber_isNeverLogged(CapturedOutput output) {
        server.expect(requestTo(URL)).andRespond(sealed(HttpStatus.CREATED, SEALED, digest(SEALED)));

        client.seal(PDF, KEY, null);

        assertThat(output.getAll()).contains("eseal_seal ishod=ok").doesNotContain(CONTROL_NUMBER);
    }

    @Test
    void sealedToString_hidesControlNumberAndContent() {
        EsealHttpClient.Sealed sealed = new EsealHttpClient.Sealed(SEALED, UUID.fromString(GUID), CONTROL_NUMBER,
                "B-LT", "sha-256=:x:", "1.0.0", false);

        assertThat(sealed.toString()).doesNotContain(CONTROL_NUMBER).contains(GUID);
    }

    @Test
    void businessReference_isReducedToCharactersTheServiceAccepts() {
        assertThat(EsealHttpClient.businessReference("Klasa: 334-06/26-10/1 — Šibenik"))
                .isEqualTo("Klasa: 334-06/26-10/1 - Sibenik");
        assertThat(EsealHttpClient.businessReference(" x ")).isEqualTo("x");
        assertThat(EsealHttpClient.businessReference("a".repeat(200))).hasSize(128);
        assertThat(EsealHttpClient.businessReference(null)).isNull();
    }

    @Test
    void reprDigest_picksSha256AmongSeveral() {
        assertThat(EsealHttpClient.sha256("sha-512=:abc=:, sha-256=:XYZ=:")).isEqualTo("XYZ=");
        assertThat(EsealHttpClient.sha256("sha-512=:abc=:")).isNull();
        assertThat(EsealHttpClient.sha256(null)).isNull();
    }

    private static DefaultResponseCreator sealed(HttpStatus status, byte[] body, String sha256) {
        return response(status, body, sha256, CONTROL_NUMBER, "B-LT");
    }

    private static DefaultResponseCreator response(HttpStatus status, byte[] body, String sha256,
                                                   String controlNumber, String sealLevel) {
        DefaultResponseCreator response = withStatus(status)
                .contentType(MediaType.APPLICATION_PDF)
                .header("Document-Guid", GUID)
                .header("Control-Number", controlNumber)
                .header("Repr-Digest", "sha-256=:" + sha256 + ":")
                .header("Service-Version", "1.0.0")
                .body(body);
        return sealLevel == null ? response : response.header("Seal-Level", sealLevel);
    }

    private static String digest(byte[] body) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
