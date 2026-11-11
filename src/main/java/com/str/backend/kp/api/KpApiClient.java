package com.str.backend.kp.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * {@code POST /poruke} eTurizmova KP API-ja ({@code Downloads/kp-api-openapi.yaml}).
 *
 * <p>Prijava je HTTP Basic tehničkim korisnikom STR-a; zaglavlje {@code Authorization} postavlja
 * {@link KpApiConfig} na {@link RestClient}, pa ga ovaj razred ni ne vidi. Ništa iz prijave se ne logira.
 *
 * <p>Preusmjerenja se ne slijede: odbijena prijava (401, 403, ili 302 na stranicu prijave) je
 * {@link Ishod#NEAUTORIZIRAN}, s opisom iz tijela odgovora odnosno odredištem preusmjerenja — da se iz
 * {@code kp_poruka.greska} i loga vidi zašto.
 *
 * <p>Nikad ne baca: svaki odgovor i svaka greška veze postaju {@link Odgovor}.
 */
public class KpApiClient {

    private static final Logger log = LoggerFactory.getLogger(KpApiClient.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Ishod jednog poziva, za odluku što s porukom. */
    public enum Ishod {
        /** KP je zaprimio poruku (201, ili 200 za već poslani {@code idPoruke}). */
        POSLANO,
        /** Primatelj nema aktivan pretinac ({@code K006}) — konačno. */
        NEMA_PRETINCA,
        /** KP je odbio poruku (npr. {@code K005}, {@code K013}) — konačno. */
        ODBIJENO,
        /** Naš zahtjev je neispravan (400) ili drugi konačni 4xx — ponavljanje ne pomaže. */
        NEISPRAVNO,
        /** Prijava odbijena (401, 403, 3xx) — konfiguracija, ne poruka: pokušaj se ne broji, poruka čeka. */
        NEAUTORIZIRAN,
        /** KP nedostupan, veza, timeout, 5xx, 409 — ishod nepoznat, ponoviti s istim {@code idPoruke}. */
        GRESKA
    }

    /** @param greska šifra i opis iz KP-a, ili razlog greške veze; {@code null} kad je poslano */
    public record Odgovor(Ishod ishod, int httpStatus, String greska) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PorukaZahtjev(UUID idPoruke, int tipPoruke, String oib, String naslov, String sadrzaj,
                                String klasa, String urBroj, List<Privitak> privici) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Privitak(String naziv, String mimeType, String opis, String sadrzaj) {

        public static Privitak pdf(String naziv, byte[] sadrzaj) {
            return new Privitak(naziv, "application/pdf", null, Base64.getEncoder().encodeToString(sadrzaj));
        }
    }

    private final RestClient restClient;

    public KpApiClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public Odgovor posalji(PorukaZahtjev poruka) {
        try {
            return restClient.post()
                    .uri("/poruke")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(poruka)
                    .exchange((request, response) -> odgovor(response.getStatusCode().value(),
                            new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8),
                            response.getHeaders().getFirst(HttpHeaders.LOCATION)));
        } catch (RestClientException e) {
            return new Odgovor(Ishod.GRESKA, 0, skrati("veza: " + najdubljiUzrok(e)));
        }
    }

    static Odgovor odgovor(int status, String tijelo) {
        return odgovor(status, tijelo, null);
    }

    static Odgovor odgovor(int status, String tijelo, String location) {
        JsonNode json = citaj(tijelo);
        String statusPoruke = tekst(json, "status");
        if (status == 200 || status == 201) {
            return new Odgovor(Ishod.POSLANO, status, null);
        }
        if (status == 422) {
            Ishod ishod = "NEMA_PRETINCA".equals(statusPoruke) ? Ishod.NEMA_PRETINCA : Ishod.ODBIJENO;
            return new Odgovor(ishod, status, skrati(tekst(json, "greska")));
        }
        if (status >= 300 && status < 400) {
            return new Odgovor(Ishod.NEAUTORIZIRAN, status,
                    skrati("prijava odbijena: HTTP " + status + " preusmjerenje na " + location));
        }
        if (status == 401 || status == 403) {
            // Spring Security (eTurizam) šalje JSON ili HTML; opis iz tijela kaže zašto (npr. CSRF, uloga).
            String razlog = json != null ? opis(json, status) : pocetak(tijelo);
            return new Odgovor(Ishod.NEAUTORIZIRAN, status,
                    skrati("prijava odbijena: HTTP " + status + (razlog == null ? "" : " — " + razlog)));
        }
        String greska = opis(json, status);
        if (status == 409 || status >= 500) {
            if (status == 409) {
                log.error("kp_api_sukob status=409 — idPoruke je iskorišten za drugu poruku ili se upravo šalje: {}",
                        greska);
            }
            return new Odgovor(Ishod.GRESKA, status, greska);
        }
        log.error("kp_api_neispravno status={} — KP API je odbio zahtjev: {}", status, greska);
        return new Odgovor(Ishod.NEISPRAVNO, status, greska);
    }

    private static String opis(JsonNode json, int status) {
        String poruka = tekst(json, "poruka");
        String greska = tekst(json, "greska");
        String opis = poruka != null ? (greska != null ? greska + ": " + poruka : poruka) : greska;
        return skrati(opis != null ? opis : "HTTP " + status);
    }

    /** Početak ne-JSON tijela (HTML greške), bez oznaka, za opis. */
    private static String pocetak(String tijelo) {
        if (tijelo == null || tijelo.isBlank()) {
            return null;
        }
        String tekst = tijelo.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").strip();
        return tekst.isEmpty() ? null : (tekst.length() <= 200 ? tekst : tekst.substring(0, 200));
    }

    private static JsonNode citaj(String tijelo) {
        if (tijelo == null || tijelo.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(tijelo);
        } catch (Exception e) {
            return null;
        }
    }

    private static String tekst(JsonNode json, String polje) {
        JsonNode v = json == null ? null : json.get(polje);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String najdubljiUzrok(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.getClass().getSimpleName() + (c.getMessage() != null ? ": " + c.getMessage() : "");
    }

    /** Tuđi tekst (odgovor KP API-ja) za log i {@code kp_poruka.greska}: bez kontrolnih znakova, skraćen. */
    static String skrati(String s) {
        if (s == null) {
            return null;
        }
        String cisto = s.replaceAll("\\p{Cntrl}", " ");
        return cisto.length() <= KpPorukaEntity.MAX_GRESKA ? cisto : cisto.substring(0, KpPorukaEntity.MAX_GRESKA);
    }
}
