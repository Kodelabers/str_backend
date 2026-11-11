package com.str.backend.kp.api;

import com.str.backend.kp.api.KpApiClient.Ishod;
import com.str.backend.kp.api.KpApiClient.PorukaZahtjev;
import com.str.backend.kp.api.KpApiClient.Privitak;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class KpApiClientTest {

    private static final UUID ID = UUID.fromString("d94345d9-5597-4a2b-b92a-813cecda347e");
    private static final String BASE = "https://kp.test/str-internal-api/kp-api";

    private MockRestServiceServer server;
    private KpApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new KpApiClient(builder.build());
    }

    private static PorukaZahtjev poruka(String klasa) {
        return new PorukaZahtjev(ID, 1741, "12312312316", "Naslov", "Sadržaj", klasa, klasa == null ? null : "2181-26-2",
                List.of(Privitak.pdf("akt.pdf", "%PDF".getBytes(StandardCharsets.UTF_8))));
    }

    private void odgovor(HttpStatus status, String tijelo) {
        server.expect(requestTo(BASE + "/poruke"))
                .andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON).body(tijelo));
    }

    /** Sesija ide kao cookie SESSION=base64(id), kao u pregledniku; tijelo je oblik iz specifikacije. */
    @Test
    void posalji_saljeSesijuITijelo() {
        String cookie = "SESSION=" + Base64.getEncoder().encodeToString("sesija-1".getBytes(StandardCharsets.UTF_8));
        server.expect(requestTo(BASE + "/poruke"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("Cookie", cookie))
                .andExpect(jsonPath("$.idPoruke").value(ID.toString()))
                .andExpect(jsonPath("$.tipPoruke").value(1741))
                .andExpect(jsonPath("$.oib").value("12312312316"))
                .andExpect(jsonPath("$.klasa").value("334-06/26-01/1"))
                .andExpect(jsonPath("$.urBroj").value("2181-26-2"))
                .andExpect(jsonPath("$.privici[0].mimeType").value("application/pdf"))
                .andExpect(jsonPath("$.privici[0].sadrzaj").value(Base64.getEncoder().encodeToString("%PDF".getBytes())))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":\"POSLANO\"}"));

        assertThat(client.posalji("sesija-1", poruka("334-06/26-01/1")).ishod()).isEqualTo(Ishod.POSLANO);
        server.verify();
    }

    @Test
    void bezKlase_klasaIUrbrojSeNeSalju() {
        server.expect(requestTo(BASE + "/poruke"))
                .andExpect(jsonPath("$.klasa").doesNotExist())
                .andExpect(jsonPath("$.urBroj").doesNotExist())
                .andRespond(withStatus(HttpStatus.CREATED));

        assertThat(client.posalji("s", poruka(null)).ishod()).isEqualTo(Ishod.POSLANO);
    }

    @Test
    void vecPoslana_200() {
        odgovor(HttpStatus.OK, "{\"status\":\"POSLANO\"}");
        assertThat(client.posalji("s", poruka(null)).ishod()).isEqualTo(Ishod.POSLANO);
    }

    @Test
    void nemaPretinca_422() {
        odgovor(HttpStatus.UNPROCESSABLE_ENTITY,
                "{\"status\":\"NEMA_PRETINCA\",\"greska\":\"K006: OIB iz poruke nema aktivan Korisnički pretinac.\"}");

        KpApiClient.Odgovor o = client.posalji("s", poruka(null));

        assertThat(o.ishod()).isEqualTo(Ishod.NEMA_PRETINCA);
        assertThat(o.greska()).startsWith("K006");
    }

    @Test
    void odbijeno_422() {
        odgovor(HttpStatus.UNPROCESSABLE_ENTITY,
                "{\"status\":\"ODBIJENO\",\"greska\":\"K013: Niste registrirani za slanje navedenog tipa poruke\"}");

        assertThat(client.posalji("s", poruka(null)).ishod()).isEqualTo(Ishod.ODBIJENO);
    }

    @Test
    void kpNedostupan_502_ponavlja() {
        odgovor(HttpStatus.BAD_GATEWAY, "{\"status\":\"GRESKA\",\"greska\":\"Greška u komunikaciji\"}");
        assertThat(client.posalji("s", poruka(null)).ishod()).isEqualTo(Ishod.GRESKA);
    }

    @Test
    void sukob_409_ponavlja() {
        odgovor(HttpStatus.CONFLICT, "{\"greska\":\"SUKOB\",\"poruka\":\"idPoruke već je iskorišten\"}");

        KpApiClient.Odgovor o = client.posalji("s", poruka(null));

        assertThat(o.ishod()).isEqualTo(Ishod.GRESKA);
        assertThat(o.greska()).isEqualTo("SUKOB: idPoruke već je iskorišten");
    }

    @Test
    void neispravanZahtjev_400_konacno() {
        odgovor(HttpStatus.BAD_REQUEST, "{\"greska\":\"NEISPRAVAN_ZAHTJEV\",\"poruka\":\"oib mora sadržavati 11 znamenki\"}");
        assertThat(client.posalji("s", poruka(null)).ishod()).isEqualTo(Ishod.NEISPRAVNO);
    }

    /** Sesiju koju servis ne prepozna NIAS preusmjerava na prijavu (302), ili odbija (401). */
    @Test
    void neprihvacenaSesija_302i401() {
        server.expect(requestTo(BASE + "/poruke")).andRespond(withStatus(HttpStatus.FOUND)
                .header("Location", "/saml2/authenticate/nias"));
        server.expect(requestTo(BASE + "/poruke")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThat(client.posalji("s", poruka(null)).ishod()).isEqualTo(Ishod.NEAUTORIZIRAN);
        assertThat(client.posalji("s", poruka(null)).ishod()).isEqualTo(Ishod.NEAUTORIZIRAN);
    }

    @Test
    void greskaVeze_ponavlja() {
        server.expect(requestTo(BASE + "/poruke")).andRespond(withException(new java.net.SocketTimeoutException("Read timed out")));

        KpApiClient.Odgovor o = client.posalji("s", poruka(null));

        assertThat(o.ishod()).isEqualTo(Ishod.GRESKA);
        assertThat(o.greska()).contains("SocketTimeoutException");
    }

    @Test
    void necitljivoTijelo_neRusi() {
        assertThat(KpApiClient.odgovor(500, "<html>").ishod()).isEqualTo(Ishod.GRESKA);
        assertThat(KpApiClient.odgovor(422, "").ishod()).isEqualTo(Ishod.ODBIJENO);
    }
}
