package com.str.backend.registries.eovlastenja;

import com.str.backend.exception.ExternalRegistryException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.str.backend.registries.eovlastenja.TestSignatures.TRUSTED;
import static com.str.backend.registries.eovlastenja.TestSignatures.representation;
import static com.str.backend.registries.eovlastenja.TestSignatures.response;
import static com.str.backend.registries.eovlastenja.TestSignatures.signed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Što klijent radi prije i oko poziva. Potpis i sadržaj odgovora pokriva
 * {@link EOvlastenjaResponseParserTest}.
 */
class EOvlastenjaHttpClientTest {

    private static final String URL = "https://roapiservistst.fina.hr/api/AuthUnionApi/GetAuthorizationUnionPermission";
    private static final String NAV_URL = "https://roapiservistst.fina.hr/api/AuthUnionApi/GetNavigationData";
    private static final String PERSON = "70000000004";
    private static final String COMPANY = "33333333360";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final EOvlastenjaHttpClient client = new EOvlastenjaHttpClient(builder.build(),
            new EOvlastenjaResponseParser(TRUSTED.cert()), URL, NAV_URL);

    /**
     * Prijava bez {@code sesija_id} je stanje registracije usluge, ne sjednice: 503, bez poziva.
     * Kao 401 frontend bi korisnika slao na ponovnu prijavu, koja ga ne donosi.
     */
    @Test
    void missingSesijaId_isUnavailable_withoutCall() {
        assertThatThrownBy(() -> client.verifyRepresentation(null, PERSON, COMPANY))
                .isInstanceOf(ExternalRegistryException.class)
                .hasMessageContaining("sesija_id");
        assertThatThrownBy(() -> client.verifyRepresentation("  ", PERSON, COMPANY))
                .isInstanceOf(ExternalRegistryException.class);
        server.verify();
    }

    /** Neočekivan format ne smije u XML, a ni kao 500 — dijagnoza mora biti čitljiva. */
    @Test
    void malformedSesijaId_isUnavailable_withoutCall() {
        assertThatThrownBy(() -> client.verifyRepresentation("abc</Sesija_Id>", PERSON, COMPANY))
                .isInstanceOf(ExternalRegistryException.class)
                .hasMessageContaining("sesija_id");
        server.verify();
    }

    @Test
    void sendsXmlRequest_andRejectsUnsignedAnswer() {
        server.expect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", "application/xml;charset=UTF-8"))
                .andExpect(content().string(containsString("<Sesija_Id>sesija-1</Sesija_Id>")))
                .andRespond(withSuccess("<nesto/>", MediaType.APPLICATION_XML));

        assertThatThrownBy(() -> client.verifyRepresentation("sesija-1", PERSON, COMPANY))
                .isInstanceOf(ExternalRegistryException.class)
                .hasMessageContaining("root");
        server.verify();
    }

    /**
     * Potpisan odgovor s hrvatskim znakovima i {@code Content-Type} bez {@code charset}. Kao
     * {@code String} bi ga Spring dekodirao ISO-8859-1 i potpis ne bi vrijedio — zato bajtovi.
     */
    @Test
    void signedUtf8Answer_withoutCharset_isAccepted() {
        server.expect(method(HttpMethod.POST)).andRespond(request -> {
            Matcher id = Pattern.compile("Id=\"(_[0-9a-f]{32})\"")
                    .matcher(((MockClientHttpRequest) request).getBodyAsString());
            assertThat(id.find()).isTrue();
            String xml;
            try {
                xml = signed(response(id.group(1), PERSON, COMPANY, representation(), "")
                        .replace("TESTNA TVRTKA", "ĐURĐEVIĆ ČŠŽ d.o.o."));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            MockClientHttpResponse answer = new MockClientHttpResponse(xml.getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
            answer.getHeaders().setContentType(MediaType.APPLICATION_XML);
            return answer;
        });

        Zastupanje z = client.verifyRepresentation("sesija-1", PERSON, COMPANY);

        assertThat(z.legalName()).isEqualTo("ĐURĐEVIĆ ČŠŽ d.o.o.");
        assertThat(z.functions()).hasSize(2);
        server.verify();
    }

    // ── nepotpisane JSON greške (HTTP 4xx) ──────────────────────────────────

    /** Izmjereno na CDU-u: šifra 100 dolazi kao HTTP 400 s JSON-om, ne u potpisanom XML-u. */
    @Test
    void jsonError100_isRegistryFailure_withCode() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"Code\":\"100\",\"Message\":\"100: Pristup metodi nije dozvoljen!\"}"));

        assertThatThrownBy(() -> client.verifyRepresentation("sesija-1", PERSON, COMPANY))
                .isInstanceOf(ExternalRegistryException.class)
                .hasMessageContaining("100");
    }

    /** Nepotpisana šifra smije samo odbiti: 203 je istekla sjednica (401), ne 503. */
    @Test
    void jsonSessionError_isSession() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("{\"Code\":\"203\",\"Message\":\"Sesija ne postoji\"}"));

        assertThatThrownBy(() -> client.verifyRepresentation("sesija-1", PERSON, COMPANY))
                .isInstanceOfSatisfying(EOvlastenjaException.class,
                        e -> assertThat(e.reason()).isEqualTo(EOvlastenjaException.Reason.SESSION));
    }

    @Test
    void jsonNotRepresentative_isNotRepresentative() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("{\"Code\":\"400\",\"Message\":\"-\"}"));

        assertThatThrownBy(() -> client.verifyRepresentation("sesija-1", PERSON, COMPANY))
                .isInstanceOfSatisfying(EOvlastenjaException.class,
                        e -> assertThat(e.reason()).isEqualTo(EOvlastenjaException.Reason.NOT_REPRESENTATIVE));
    }

    /** Nepoznata šifra je 503 s njom u poruci — nikad odobrenje. */
    @Test
    void jsonUnknownCode_isRegistryFailure() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("{\"Code\":\"999\",\"Message\":\"?\"}"));

        assertThatThrownBy(() -> client.verifyRepresentation("sesija-1", PERSON, COMPANY))
                .isInstanceOf(ExternalRegistryException.class)
                .hasMessageContaining("šifra 999")
                .hasNoCause();
    }

    /** Izmjereno na CDU-u: GetNavigationData šifru 100 vraća kao XML {@code <Error>}, ne kao JSON. */
    @Test
    void navigation_xmlError100_isRegistryFailure_withCode() {
        server.expect(requestTo(NAV_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_XML)
                .body("<Error xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\" "
                        + "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" "
                        + "xmlns=\"http://eovlastenja.fina.hr/authorizationbase/v2\"><Code>100</Code>"
                        + "<Message>100: Pristup metodi 'https://roapiservistst.fina.hr/api/AuthUnionApi/GetNavigationData' "
                        + "servisa [AuthorizationSvc] certifikatom CN=e-Turizam nije dozvoljen!</Message></Error>"));

        assertThatThrownBy(() -> client.representedCompanies("sesija-1", PERSON))
                .isInstanceOf(ExternalRegistryException.class)
                .hasMessageContaining("šifra 100");
    }

    /** XML greška sa šifrom sjednice znači istu stvar kao i JSON — odbijanje, ne odobrenje. */
    @Test
    void xmlSessionError_isSession() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_XML)
                .body("<Error xmlns=\"http://eovlastenja.fina.hr/authorizationbase/v2\"><Code>203</Code><Message>-</Message></Error>"));

        assertThatThrownBy(() -> client.verifyRepresentation("sesija-1", PERSON, COMPANY))
                .isInstanceOfSatisfying(EOvlastenjaException.class,
                        e -> assertThat(e.reason()).isEqualTo(EOvlastenjaException.Reason.SESSION));
    }

    /** Početak tijela greške za log: skraćen, bez prelaska retka, bez OIB-a i sesija_id. */
    @Test
    void bodyPreview_isShortAndMasked() {
        String body = "{\"errors\":{\"PersonOIB\":[\"12312312316 nije ispravan\"],"
                + "\"Sesija_Id\":[\"3B51-9ACB-EAE9-801A-9A1D\"]}}\r\nINFO lažni redak" + "x".repeat(500);

        String preview = EOvlastenjaHttpClient.bodyPreview(body.getBytes(StandardCharsets.UTF_8));

        assertThat(preview).contains("PersonOIB").contains("<oib>").contains("<sesija>")
                .doesNotContain("12312312316").doesNotContain("3B51-9ACB").doesNotContain("\n")
                .hasSizeLessThanOrEqualTo(301);
    }

    /** Tijelo koje nije JSON (HTML proxyja): 503, tijelo ostaje u uzroku za dijagnozu. */
    @Test
    void htmlErrorBody_isUnavailable() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.TEXT_HTML).body("<html>Bad Request</html>"));

        assertThatThrownBy(() -> client.verifyRepresentation("sesija-1", PERSON, COMPANY))
                .isInstanceOf(ExternalRegistryException.class)
                .hasMessageContaining("nisu dostupna");
    }

    /** Šifra s prelaskom retka ne ide u log ni u poruku (log forging) — tretira se kao da je nema. */
    @Test
    void jsonCodeWithNewline_isIgnored() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("{\"Code\":\"100\\nINFO lažni redak\"}"));

        assertThatThrownBy(() -> client.verifyRepresentation("sesija-1", PERSON, COMPANY))
                .isInstanceOf(ExternalRegistryException.class)
                .hasMessageContaining("nisu dostupna")
                .hasMessageNotContaining("lažni");
    }

    // ── popis tvrtki (GetNavigationData) ─────────────────────────────────────

    @Test
    void navigation_sendsNavigationRequest_andReturnsCompanies() {
        server.expect(requestTo(NAV_URL))
                .andExpect(content().string(containsString("<NavigationDataRequest")))
                .andExpect(content().string(containsString("<PersonOIB>" + PERSON + "</PersonOIB>")))
                .andRespond(request -> {
                    Matcher id = Pattern.compile("Id=\"(_[0-9a-f]{32})\"")
                            .matcher(((MockClientHttpRequest) request).getBodyAsString());
                    assertThat(id.find()).isTrue();
                    String xml = TestSignatures.navigationResponse(id.group(1), PERSON, "", "");
                    MockClientHttpResponse answer = new MockClientHttpResponse(xml.getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
                    answer.getHeaders().setContentType(MediaType.APPLICATION_XML);
                    return answer;
                });

        assertThat(client.representedCompanies("sesija-1", PERSON))
                .containsExactly(new ZastupanaTvrtka("85821130368", "FINANCIJSKA AGENCIJA"));
        server.verify();
    }

    @Test
    void navigation_accessNotAllowed_isRegistryFailure() {
        server.expect(requestTo(NAV_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("{\"Code\":\"100\",\"Message\":\"nije dozvoljen\"}"));

        assertThatThrownBy(() -> client.representedCompanies("sesija-1", PERSON))
                .isInstanceOf(ExternalRegistryException.class)
                .hasMessageContaining("100");
    }

    @Test
    void navigation_withoutSesijaId_isUnavailable_withoutCall() {
        assertThatThrownBy(() -> client.representedCompanies(null, PERSON))
                .isInstanceOf(ExternalRegistryException.class)
                .hasMessageContaining("sesija_id");
        server.verify();
    }

    @Test
    void serverError_isUnavailable() {
        server.expect(method(HttpMethod.POST)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> client.verifyRepresentation("sesija-1", PERSON, COMPANY))
                .isInstanceOf(ExternalRegistryException.class)
                .hasMessageContaining("nisu dostupna");
    }
}
