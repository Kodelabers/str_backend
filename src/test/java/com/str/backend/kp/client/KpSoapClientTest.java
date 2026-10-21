package com.str.backend.kp.client;

import com.str.backend.kp.KpPrimatelj;
import com.str.backend.kp.KpProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.saaj.SaajSoapMessageFactory;
import org.springframework.ws.transport.http.HttpUrlConnectionMessageSender;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stvarna SOAP serijalizacija prema lokalnom HTTP poslužitelju: oblik GSB omotnice, ServiceId,
 * SOAPAction, poslovna poruka u {@code Content/Data} (EMBEDDED i BASE64) i čitanje odgovora s
 * obje razine grešaka. Obostrani TLS ovdje nije — njega slaže {@code KpConfig}.
 */
class KpSoapClientTest {

    private static final String GSB = "http://apis-it.hr/umu/2013/types/gsb";
    private static final String FO_NS = "http://apis-it.hr/umu/2013/types/kp";
    private static final String PS_NS = "http://www.apis-it.hr/umu/2018/elements/ReceiveMessage";
    private static final UUID ID_PORUKE = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
    private static final byte[] PDF = "%PDF-akt".getBytes(StandardCharsets.UTF_8);

    private HttpServer server;
    private volatile String requestBody;
    private volatile String soapAction;
    private volatile String responseBody;
    private volatile int responseStatus = 200;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/kp", exchange -> {
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            soapAction = exchange.getRequestHeaders().getFirst("SOAPAction");
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/xml; charset=utf-8");
            exchange.sendResponseHeaders(responseStatus, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void posaljiFo_embedded_buildsEnvelopeAndReadsResponse() {
        responseBody = envelope(content("EMBEDDED", """
                <kp:Odgovor xmlns:kp="%s">
                  <kp:IdPorukePosiljatelja>%s</kp:IdPorukePosiljatelja>
                  <kp:DatumVrijemeObrade>2026-10-21T10:00:00</kp:DatumVrijemeObrade>
                </kp:Odgovor>""".formatted(FO_NS, ID_PORUKE)), "");

        KpOdgovor odgovor = client("EMBEDDED").posalji(poruka(new KpPrimatelj(KpPrimatelj.Tip.FO, "12345678903", null)));

        assertThat(odgovor.ok()).isTrue();
        assertThat(odgovor.obradeno()).isEqualTo(Instant.parse("2026-10-21T08:00:00Z"));
        assertThat(soapAction).contains("http://www.apis-it.hr/umu/2013/services/GSBService/sendMessage");
        assertThat(requestBody).containsPattern("ServiceId>123<");
        assertThat(requestBody).containsPattern("SenderId>777<");
        assertThat(requestBody).containsPattern("encoding=\"EMBEDDED\"");
        assertThat(requestBody).contains(FO_NS);
        assertThat(requestBody).containsPattern("PinPrimatelja>12345678903<");
        assertThat(requestBody).containsPattern("OznakaDrzave>HR<");
        assertThat(requestBody).containsPattern("IdPoruke>" + ID_PORUKE + "<");
        assertThat(requestBody).containsPattern("TipPoruke>9001<");
        assertThat(requestBody).containsPattern("Predmet>Obavijest o suspenziji registracijskog broja<");
        assertThat(requestBody).containsPattern("Sadrzaj>" + Pattern.quote(base64("Tekst poruke")) + "<");
        assertThat(requestBody).containsPattern("PorukaSPravnimUcinkom>T<");
        assertThat(requestBody).containsPattern("PotrebnaPotvrdaPrimitka>T<");
        assertThat(requestBody).containsPattern("PotrebnaSuglasnostZaPregledPoruke>F<");
        assertThat(requestBody).containsPattern("RokDani>8<");
        assertThat(requestBody).containsPattern("GodineCuvanja>11<");
        assertThat(requestBody).containsPattern("KlasaBrDok>UP/I-334-01/26-01/55<");
        assertThat(requestBody).containsPattern("JedinstveniIdentifikatorPismena>pismeno-1<");
        assertThat(requestBody).containsPattern("Data encoding=\"BASE64\">" + Pattern.quote(Base64.getEncoder().encodeToString(PDF)));
    }

    @Test
    void posaljiPs_base64_sendsEncodedPayloadAndReadsBusinessErrors() {
        responseBody = envelope(content("BASE64", base64("""
                <ps:ZaprimiPorukuResponse xmlns:ps="%s"><ps:ZaprimiPorukuOdgovor>
                  <ps:IdPorukePosiljatelja>%s</ps:IdPorukePosiljatelja>
                  <ps:StatusZaprimanja>Greska</ps:StatusZaprimanja>
                  <ps:TsObrade>21.10.2026 10:00:00.000</ps:TsObrade>
                  <ps:Pogreske><ps:Pogreska><ps:IndikatorPogreske>Z005</ps:IndikatorPogreske>
                    <ps:PorukaPogreske>JIPS iz poruke nema aktivan Korisnički pretinac.</ps:PorukaPogreske>
                    <ps:GUID>0f8fad5b-d9cb-469f-a165-70867728950f</ps:GUID>
                    <ps:VrijemePogreske>21.10.2026 10:00:00.000</ps:VrijemePogreske></ps:Pogreska></ps:Pogreske>
                </ps:ZaprimiPorukuOdgovor></ps:ZaprimiPorukuResponse>""".formatted(PS_NS, ID_PORUKE))), "");

        KpOdgovor odgovor = client("BASE64").posalji(poruka(new KpPrimatelj(KpPrimatelj.Tip.PS, "98765432106", "001")));

        assertThat(odgovor.sifre()).containsExactly("Z005");
        assertThat(odgovor.opis()).contains("nema aktivan Korisnički pretinac")
                .contains("[guid 0f8fad5b-d9cb-469f-a165-70867728950f]");
        assertThat(odgovor.gsbId()).isEqualTo("1b4e28ba-2fa1-11d2-883f-0016d3cca427");
        assertThat(requestBody).containsPattern("ServiceId>125<");
        String payload = decodedData(requestBody);
        assertThat(payload).contains(PS_NS);
        assertThat(payload).containsPattern("IPS>98765432106<");
        assertThat(payload).containsPattern("IzvorRegistar>001<");
        assertThat(payload).containsPattern("IdPorukeURegistru>9101<");
        assertThat(payload).containsPattern("IdPosiljatelja>789<");
        assertThat(payload).containsPattern("Sadrzaj>" + Pattern.quote(base64("Tekst poruke")) + "<");
        assertThat(payload).containsPattern("TsSlanja>\\d{2}\\.\\d{2}\\.\\d{4} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}<");
    }

    @Test
    void envelopeErrorWithoutPayload_isReturnedAsCode() {
        responseBody = envelope("", "<gsb:Errors><gsb:Error><gsb:ErrorCode>M004</gsb:ErrorCode>"
                + "<gsb:ErrorMessage>ID usluge nije evidentiran.</gsb:ErrorMessage></gsb:Error></gsb:Errors>");

        KpOdgovor odgovor = client("EMBEDDED").posalji(poruka(new KpPrimatelj(KpPrimatelj.Tip.FO, "12345678903", null)));

        assertThat(odgovor.sifre()).containsExactly("M004");
    }

    /** FO shema traži {@code s[0-9]{3}}, a stižu K…; odgovor se ne validira, pa šifra mora proći. */
    @Test
    void foErrorCode_notMatchingSchemaPattern_isStillRead() {
        responseBody = envelope(content("EMBEDDED", """
                <kp:Odgovor xmlns:kp="%s"><kp:Greske><kp:Greska>
                  <kp:SifraGreske>K006</kp:SifraGreske><kp:OpisGreske>OIB nema aktivan KP.</kp:OpisGreske>
                </kp:Greska></kp:Greske></kp:Odgovor>""".formatted(FO_NS)), "");

        KpOdgovor odgovor = client("EMBEDDED").posalji(poruka(new KpPrimatelj(KpPrimatelj.Tip.FO, "12345678903", null)));

        assertThat(odgovor.sifre()).containsExactly("K006");
    }

    @Test
    void aktivnostFo_readsStatusDatesAndReceipt() {
        byte[] potvrda = "%PDF-potvrda".getBytes(StandardCharsets.UTF_8);
        responseBody = envelope(content("EMBEDDED", """
                <kp:DohvatiAktivnostPorukeResponse xmlns:kp="%s">
                  <kp:SenderId>777</kp:SenderId><kp:IdPosiljatelja>456</kp:IdPosiljatelja>
                  <kp:IdUpitaPosiljatelja>0f8fad5b-d9cb-469f-a165-70867728950f</kp:IdUpitaPosiljatelja>
                  <kp:TsObrade>21.10.2026 10:00:00.000</kp:TsObrade><kp:PinPrimatelja>12345678903</kp:PinPrimatelja>
                  <kp:UuidPoruke>%s</kp:UuidPoruke>
                  <kp:DatumIVrijemePrimitkaPoruke>2026-10-21T10:00:00+02:00</kp:DatumIVrijemePrimitkaPoruke>
                  <kp:IstekRoka>2026-10-29T10:00:00+01:00</kp:IstekRoka>
                  <kp:DatumIVrijemeAktivnosti>2026-10-22T09:30:00+02:00</kp:DatumIVrijemeAktivnosti>
                  <kp:StatusAktivnosti>2</kp:StatusAktivnosti>
                  <kp:PdfPotvrdeElPotpisa>%s</kp:PdfPotvrdeElPotpisa>
                  <kp:StatusOdgovora>OK</kp:StatusOdgovora>
                </kp:DohvatiAktivnostPorukeResponse>""".formatted(FO_NS, ID_PORUKE,
                Base64.getEncoder().encodeToString(potvrda))), "");

        KpAktivnost aktivnost = client("EMBEDDED").aktivnost(
                new KpPrimatelj(KpPrimatelj.Tip.FO, "12345678903", null), ID_PORUKE.toString());

        assertThat(aktivnost.ok()).isTrue();
        assertThat(aktivnost.status()).isEqualTo(2);
        assertThat(aktivnost.primitak()).isEqualTo(Instant.parse("2026-10-21T08:00:00Z"));
        assertThat(aktivnost.aktivnost()).isEqualTo(Instant.parse("2026-10-22T07:30:00Z"));
        assertThat(aktivnost.istekRoka()).isEqualTo(Instant.parse("2026-10-29T09:00:00Z"));
        assertThat(aktivnost.pdfPotvrde()).isEqualTo(potvrda);
        assertThat(requestBody).containsPattern("ServiceId>129<");
        assertThat(requestBody).containsPattern("UuidPoruke>" + ID_PORUKE + "<");
    }

    /** „Greska" bez ijedne šifre ne smije proći kao uspjeh. */
    @Test
    void aktivnostPs_errorStatusWithoutCodes_isNotOk() {
        responseBody = envelope(content("EMBEDDED", """
                <ps:DohvatiAktivnostPorukeResponse xmlns:ps="%s"><ps:DohvatiAktivnostPorukeOdgovor>
                  <ps:SenderId>777</ps:SenderId><ps:IdPosiljatelja>789</ps:IdPosiljatelja>
                  <ps:IdUpitaPosiljatelja>0f8fad5b-d9cb-469f-a165-70867728950f</ps:IdUpitaPosiljatelja>
                  <ps:TsObrade>21.10.2026 10:00:00.000</ps:TsObrade><ps:IPS>98765432106</ps:IPS>
                  <ps:IzvorRegistar>001</ps:IzvorRegistar><ps:UuidPoruke>%s</ps:UuidPoruke>
                  <ps:StatusOdgovora>Greska</ps:StatusOdgovora>
                </ps:DohvatiAktivnostPorukeOdgovor></ps:DohvatiAktivnostPorukeResponse>""".formatted(PS_NS, ID_PORUKE)), "");

        KpAktivnost aktivnost = client("EMBEDDED").aktivnost(
                new KpPrimatelj(KpPrimatelj.Tip.PS, "98765432106", "001"), ID_PORUKE.toString());

        assertThat(aktivnost.ok()).isFalse();
        assertThat(requestBody).containsPattern("ServiceId>128<");
    }

    /** Odgovor bez poslovnog sadržaja i bez šifre ne govori ništa o ishodu — to nije uspjeh. */
    @Test
    void emptyResponse_isTransportException() {
        responseBody = envelope("", "");

        assertThatThrownBy(() -> client("EMBEDDED").posalji(poruka(new KpPrimatelj(KpPrimatelj.Tip.FO, "12345678903", null))))
                .isInstanceOf(KpTransportException.class)
                .hasMessageContaining("1b4e28ba-2fa1-11d2-883f-0016d3cca427");
    }

    @Test
    void httpError_isTransportException() {
        responseStatus = 503;
        responseBody = "Service Unavailable";

        assertThatThrownBy(() -> client("EMBEDDED").posalji(poruka(new KpPrimatelj(KpPrimatelj.Tip.FO, "12345678903", null))))
                .isInstanceOf(KpTransportException.class);
    }

    /** Poslovni sadržaj dolazi izvana — DTD/vanjski entiteti se ne obrađuju (XXE). */
    @Test
    void base64PayloadWithDoctype_isRejected() {
        responseBody = envelope(content("BASE64", base64("""
                <!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]>
                <kp:Odgovor xmlns:kp="%s"><kp:IdPorukePosiljatelja>&e;</kp:IdPorukePosiljatelja></kp:Odgovor>"""
                .formatted(FO_NS))), "");

        assertThatThrownBy(() -> client("EMBEDDED").posalji(poruka(new KpPrimatelj(KpPrimatelj.Tip.FO, "12345678903", null))))
                .isInstanceOf(KpTransportException.class);
    }

    // --- pomoćno --------------------------------------------------------------------------

    private KpSoapClient client(String encoding) {
        HttpUrlConnectionMessageSender sender = new HttpUrlConnectionMessageSender();
        sender.setConnectionTimeout(Duration.ofSeconds(5));
        sender.setReadTimeout(Duration.ofSeconds(5));
        SaajSoapMessageFactory factory = new SaajSoapMessageFactory();
        factory.afterPropertiesSet();
        WebServiceTemplate template = new WebServiceTemplate(factory);
        Jaxb2Marshaller marshaller = new Jaxb2Marshaller();
        marshaller.setContextPath("hr.apis.kp.gsb");
        try {
            marshaller.afterPropertiesSet();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        template.setMarshaller(marshaller);
        template.setUnmarshaller(marshaller);
        template.setMessageSender(sender);
        template.setDefaultUri("http://127.0.0.1:" + server.getAddress().getPort() + "/kp");
        return new KpSoapClient(template, properties(encoding));
    }

    private static KpProperties properties(String encoding) {
        return new KpProperties(true, "http://unused", null, null, "x", "x", null, null, null,
                "777", "456", "789", "001", encoding, "application/xml", 3,
                true, false, true, false, 8, false, false, 11,
                Map.of("suspenzija", 9001), Map.of("suspenzija", 9101), Map.of(), null);
    }

    private static KpPoruka poruka(KpPrimatelj primatelj) {
        return new KpPoruka(primatelj, ID_PORUKE, primatelj.tip() == KpPrimatelj.Tip.FO ? 9001 : 9101,
                "Obavijest o suspenziji registracijskog broja", "Tekst poruke", "UP/I-334-01/26-01/55",
                "529-06/26-7", "suspenzija-HR012345678901234567.pdf", PDF, "pismeno-1",
                Instant.parse("2026-10-21T08:00:00Z"));
    }

    private static String envelope(String content, String errors) {
        return """
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
                  <soapenv:Body>
                    <gsb:SendMessageResponse xmlns:gsb="%s">%s%s%s</gsb:SendMessageResponse>
                  </soapenv:Body>
                </soapenv:Envelope>""".formatted(GSB, HEADER, content, errors);
    }

    /** Zaglavlje odgovora kakvo opisuje spec. 3.2 — s GsbId-jem koji dodjeljuje SZP. */
    private static final String HEADER = """
            <gsb:MessageHeader><gsb:SenderId>777</gsb:SenderId><gsb:ServiceId>125</gsb:ServiceId>
              <gsb:MessageId>0f8fad5b-d9cb-469f-a165-70867728950e</gsb:MessageId>
              <gsb:GsbId>1b4e28ba-2fa1-11d2-883f-0016d3cca427</gsb:GsbId>
              <gsb:SenderTimeStamp>2026-10-21T10:00:00+02:00</gsb:SenderTimeStamp>
              <gsb:ExchangeStatus>1</gsb:ExchangeStatus></gsb:MessageHeader>""";

    private static String content(String encoding, String data) {
        return "<gsb:Content><gsb:MimeType>application/xml</gsb:MimeType><gsb:Data encoding=\"" + encoding + "\">"
                + data + "</gsb:Data></gsb:Content>";
    }

    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodedData(String soap) {
        Matcher m = Pattern.compile("Data encoding=\"BASE64\">([A-Za-z0-9+/=\\s]+)<").matcher(soap);
        assertThat(m.find()).as("BASE64 Data u zahtjevu").isTrue();
        return new String(Base64.getMimeDecoder().decode(m.group(1)), StandardCharsets.UTF_8);
    }
}
