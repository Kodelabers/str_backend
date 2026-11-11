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
 * <p>Prijava je NIAS sesija službenika: šalje se cookie {@code SESSION=<base64(id)>}, kao
 * {@code SharedSessionCookieInterceptor}, ali s izričito predanim ID-om sesije — slanje ide izvan niti
 * zahtjeva, gdje {@code RequestContextHolder} ne postoji. Vrijednost cookieja se nikad ne logira.
 *
 * <p>Preusmjerenja se ne slijede: sesija koju servis ne prepozna dobije 302 na NIAS prijavu, što je
 * {@link Ishod#NEAUTORIZIRAN}, a ne HTML stranica koja bi izgledala kao nečitljiv odgovor.
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
        /** Sesija ne vrijedi (401, 403, 3xx) — pokušat će druga sesija, pokušaj se ne broji. */
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

    public Odgovor posalji(String sessionId, PorukaZahtjev poruka) {
        String cookie = "SESSION=" + Base64.getEncoder().encodeToString(sessionId.getBytes(StandardCharsets.UTF_8));
        try {
            return restClient.post()
                    .uri("/poruke")
                    .header(HttpHeaders.COOKIE, cookie)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(poruka)
                    .exchange((request, response) -> odgovor(response.getStatusCode().value(),
                            new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
        } catch (RestClientException e) {
            return new Odgovor(Ishod.GRESKA, 0, skrati("veza: " + najdubljiUzrok(e)));
        }
    }

    static Odgovor odgovor(int status, String tijelo) {
        JsonNode json = citaj(tijelo);
        String statusPoruke = tekst(json, "status");
        if (status == 200 || status == 201) {
            return new Odgovor(Ishod.POSLANO, status, null);
        }
        if (status == 422) {
            Ishod ishod = "NEMA_PRETINCA".equals(statusPoruke) ? Ishod.NEMA_PRETINCA : Ishod.ODBIJENO;
            return new Odgovor(ishod, status, skrati(tekst(json, "greska")));
        }
        if (status == 401 || status == 403 || (status >= 300 && status < 400)) {
            return new Odgovor(Ishod.NEAUTORIZIRAN, status, "sesija nije prihvaćena (HTTP " + status + ")");
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
