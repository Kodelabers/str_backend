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
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Što klijent radi prije i oko poziva. Potpis i sadržaj odgovora pokriva
 * {@link EOvlastenjaResponseParserTest}.
 */
class EOvlastenjaHttpClientTest {

    private static final String URL = "https://roapiservistst.fina.hr/api/AuthUnionApi/GetAuthorizationUnionPermission";
    private static final String PERSON = "70000000004";
    private static final String COMPANY = "33333333360";

    private final RestClient.Builder builder = RestClient.builder().baseUrl(URL);
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final EOvlastenjaHttpClient client = new EOvlastenjaHttpClient(builder.build(), new EOvlastenjaResponseParser(TRUSTED.cert()));

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

    @Test
    void serverError_isUnavailable() {
        server.expect(method(HttpMethod.POST)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> client.verifyRepresentation("sesija-1", PERSON, COMPANY))
                .isInstanceOf(ExternalRegistryException.class)
                .hasMessageContaining("nisu dostupna");
    }
}
