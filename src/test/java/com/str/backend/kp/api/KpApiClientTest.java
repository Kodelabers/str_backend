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
        KpApiProperties properties = new KpApiProperties(true, BASE, "short-term-rental", "tajna", 1741, null, null,
                null, null, null, null, null, null, null, null);
        RestClient.Builder builder = KpApiConfig.configure(RestClient.builder(), properties);
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

    /** Prijava je HTTP Basic tehničkim korisnikom; tijelo je oblik iz specifikacije. */
    @Test
    void posalji_saljeBasicPrijavuITijelo() {
        String basic = "Basic " + Base64.getEncoder().encodeToString("short-term-rental:tajna".getBytes(StandardCharsets.UTF_8));
        server.expect(requestTo(BASE + "/poruke"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("Authorization", basic))
                .andExpect(jsonPath("$.idPoruke").value(ID.toString()))
                .andExpect(jsonPath("$.tipPoruke").value(1741))
                .andExpect(jsonPath("$.oib").value("12312312316"))
                .andExpect(jsonPath("$.klasa").value("334-06/26-01/1"))
                .andExpect(jsonPath("$.urBroj").value("2181-26-2"))
                .andExpect(jsonPath("$.privici[0].mimeType").value("application/pdf"))
                .andExpect(jsonPath("$.privici[0].sadrzaj").value(Base64.getEncoder().encodeToString("%PDF".getBytes())))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":\"POSLANO\"}"));

        assertThat(client.posalji(poruka("334-06/26-01/1")).ishod()).isEqualTo(Ishod.POSLANO);
        server.verify();
    }

    /** Lozinka sa znakom izvan ISO-8859-1 ne ruši start; kodira se UTF-8, kao što KP API dekodira. */
    @Test
    void lozinkaSaZnakovimaIzvanLatin1_utf8() {
        KpApiProperties properties = new KpApiProperties(true, BASE, "short-term-rental", "lozinka-čćž€", 1741, null,
                null, null, null, null, null, null, null, null, null);
        RestClient.Builder builder = KpApiConfig.configure(RestClient.builder(), properties);
        MockRestServiceServer utf8Server = MockRestServiceServer.bindTo(builder).build();
        String basic = "Basic " + Base64.getEncoder()
                .encodeToString("short-term-rental:lozinka-čćž€".getBytes(StandardCharsets.UTF_8));
        utf8Server.expect(requestTo(BASE + "/poruke"))
                .andExpect(header("Authorization", basic))
                .andRespond(withStatus(HttpStatus.CREATED));

        assertThat(new KpApiClient(builder.build()).posalji(poruka(null)).ishod()).isEqualTo(Ishod.POSLANO);
        utf8Server.verify();
    }

    @Test
    void bezKlase_klasaIUrbrojSeNeSalju() {
        server.expect(requestTo(BASE + "/poruke"))
                .andExpect(jsonPath("$.klasa").doesNotExist())
                .andExpect(jsonPath("$.urBroj").doesNotExist())
                .andRespond(withStatus(HttpStatus.CREATED));

        assertThat(client.posalji(poruka(null)).ishod()).isEqualTo(Ishod.POSLANO);
    }

    @Test
    void vecPoslana_200() {
        odgovor(HttpStatus.OK, "{\"status\":\"POSLANO\"}");
        assertThat(client.posalji(poruka(null)).ishod()).isEqualTo(Ishod.POSLANO);
    }

    @Test
    void nemaPretinca_422() {
        odgovor(HttpStatus.UNPROCESSABLE_ENTITY,
                "{\"status\":\"NEMA_PRETINCA\",\"greska\":\"K006: OIB iz poruke nema aktivan Korisnički pretinac.\"}");

        KpApiClient.Odgovor o = client.posalji(poruka(null));

        assertThat(o.ishod()).isEqualTo(Ishod.NEMA_PRETINCA);
        assertThat(o.greska()).startsWith("K006");
    }

    @Test
    void odbijeno_422() {
        odgovor(HttpStatus.UNPROCESSABLE_ENTITY,
                "{\"status\":\"ODBIJENO\",\"greska\":\"K013: Niste registrirani za slanje navedenog tipa poruke\"}");

        assertThat(client.posalji(poruka(null)).ishod()).isEqualTo(Ishod.ODBIJENO);
    }

    @Test
    void kpNedostupan_502_ponavlja() {
        odgovor(HttpStatus.BAD_GATEWAY, "{\"status\":\"GRESKA\",\"greska\":\"Greška u komunikaciji\"}");
        assertThat(client.posalji(poruka(null)).ishod()).isEqualTo(Ishod.GRESKA);
    }

    @Test
    void sukob_409_ponavlja() {
        odgovor(HttpStatus.CONFLICT, "{\"greska\":\"SUKOB\",\"poruka\":\"idPoruke već je iskorišten\"}");

        KpApiClient.Odgovor o = client.posalji(poruka(null));

        assertThat(o.ishod()).isEqualTo(Ishod.GRESKA);
        assertThat(o.greska()).isEqualTo("SUKOB: idPoruke već je iskorišten");
    }

    @Test
    void neispravanZahtjev_400_konacno() {
        odgovor(HttpStatus.BAD_REQUEST, "{\"greska\":\"NEISPRAVAN_ZAHTJEV\",\"poruka\":\"oib mora sadržavati 11 znamenki\"}");
        assertThat(client.posalji(poruka(null)).ishod()).isEqualTo(Ishod.NEISPRAVNO);
    }

    /** Odbijena prijava: preusmjerenje na prijavu (302) ili 401/403 — opis kaže zašto. */
    @Test
    void neprihvacenaSesija_302i401() {
        server.expect(requestTo(BASE + "/poruke")).andRespond(withStatus(HttpStatus.FOUND)
                .header("Location", "/saml2/authenticate/nias"));
        server.expect(requestTo(BASE + "/poruke")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThat(client.posalji(poruka(null)).ishod()).isEqualTo(Ishod.NEAUTORIZIRAN);
        assertThat(client.posalji(poruka(null)).ishod()).isEqualTo(Ishod.NEAUTORIZIRAN);
    }

    @Test
    void greskaVeze_ponavlja() {
        server.expect(requestTo(BASE + "/poruke")).andRespond(withException(new java.net.SocketTimeoutException("Read timed out")));

        KpApiClient.Odgovor o = client.posalji(poruka(null));

        assertThat(o.ishod()).isEqualTo(Ishod.GRESKA);
        assertThat(o.greska()).contains("SocketTimeoutException");
    }

    @Test
    void necitljivoTijelo_neRusi() {
        assertThat(KpApiClient.odgovor(500, "<html>").ishod()).isEqualTo(Ishod.GRESKA);
        assertThat(KpApiClient.odgovor(422, "").ishod()).isEqualTo(Ishod.ODBIJENO);
    }

    /** 403 nosi opis iz tijela (JSON ili HTML), 302 odredište — da se iz loga vidi zašto. */
    @Test
    void odbijenaPrijava_opisIzOdgovora() {
        KpApiClient.Odgovor json = KpApiClient.odgovor(403, "{\"error\":\"Forbidden\",\"message\":\"Invalid CSRF token\"}", null);
        KpApiClient.Odgovor html = KpApiClient.odgovor(403, "<html><body><h1>Pristup odbijen</h1></body></html>", null);
        KpApiClient.Odgovor redirect = KpApiClient.odgovor(302, "", "/saml2/authenticate/nias");

        assertThat(json.ishod()).isEqualTo(Ishod.NEAUTORIZIRAN);
        assertThat(html.greska()).isEqualTo("prijava odbijena: HTTP 403 — Pristup odbijen");
        assertThat(redirect.greska()).isEqualTo("prijava odbijena: HTTP 302 preusmjerenje na /saml2/authenticate/nias");
    }
}
