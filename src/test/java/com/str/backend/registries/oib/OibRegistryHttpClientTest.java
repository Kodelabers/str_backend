package com.str.backend.registries.oib;

import com.str.backend.exception.ExternalRegistryException;
import com.str.backend.lessor.RegistrySubject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Ishodi poziva OIB sustava. Nedostupan registar mora biti 503 ({@link ExternalRegistryException}),
 * a nepoznat OIB „nema subjekta" — to su dvije različite poruke korisniku.
 */
class OibRegistryHttpClientTest {

    private static final String OIB = "12312312316";
    private static final String URL =
            "http://oib.test/str-internal-api/pretraga-registra/oib?oib=" + OIB;

    private MockRestServiceServer server;
    private OibRegistryHttpClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://oib.test/str-internal-api");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new OibRegistryHttpClient(builder.build());
    }

    @Test
    void notFound_meansUnknownSubject() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.findByOib(OIB)).isEmpty();
        server.verify();
    }

    @Test
    void emptyBody_meansUnknownSubject() {
        server.expect(requestTo(URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(client.findByOib(OIB)).isEmpty();
    }

    @Test
    void serverError_isRegistryUnavailable() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.findByOib(OIB))
                .isInstanceOf(ExternalRegistryException.class)
                .extracting(e -> ((ExternalRegistryException) e).getRegistry())
                .isEqualTo("OIB");
    }

    /** Izmjereno 28.09.2026. s CDU kutije: anonimni poziv dobije 302 na NIAS prijavu. */
    @Test
    void redirectToNiasLogin_isReportedAsUnauthorized() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.FOUND)
                .location(java.net.URI.create("http://et2-test-internal-eturizam.gov.hr/saml2/authenticate/nias")));

        assertThatThrownBy(() -> client.findByOib(OIB))
                .isInstanceOf(ExternalRegistryException.class)
                .hasMessageContaining("bez prijave")
                .hasMessageContaining("302");
    }

    @Test
    void timeout_isRegistryUnavailable() {
        server.expect(requestTo(URL)).andRespond(withException(new SocketTimeoutException("read timed out")));

        assertThatThrownBy(() -> client.findByOib(OIB)).isInstanceOf(ExternalRegistryException.class);
    }

    @Test
    void mapsSubject_fromResponse() {
        server.expect(requestTo(URL)).andRespond(withSuccess(new ClassPathResource(
                "oib-registry/fizicka-osoba-fg.json"), MediaType.APPLICATION_JSON));

        RegistrySubject s = client.findByOib(OIB).orElseThrow();

        assertThat(s.firstName()).isEqualTo("PERO");
        assertThat(s.streetNumber()).isEqualTo("14A");
    }

    @Test
    void malformedBody_isRegistryUnavailable() {
        server.expect(requestTo(URL)).andRespond(withSuccess("<html>proxy error</html>", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.findByOib(OIB)).isInstanceOf(ExternalRegistryException.class);
    }
}
