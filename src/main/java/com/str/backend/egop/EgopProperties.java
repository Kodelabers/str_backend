package com.str.backend.egop;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Sva eGOP transport/auth konfiguracija na jednom mjestu.
 *
 * <p>Postoje <b>dva odvojena identiteta</b> (potvrđeno referentnim klijentom):
 * {@code username}/{@code password} su NTLM kredencijali za HTTP sloj, a
 * {@code appDomain}\{@code appUsername} ide u {@code userName} polje SOAP payloada.
 * Ranije su se gradili na dva mjesta s različitim defaultima — zato
 * {@link #qualifiedAppUsername()} postoji samo ovdje.
 *
 * <p>Adresa se konfigurira <b>jednom</b>, kroz {@code base-url}; četiri endpointa se iz
 * njega izvode. {@code base-url} namjerno <b>nema default</b>: eGOP okruženja se razlikuju
 * i po hostu i po prefiksu putanje (test na InfoDom mreži je
 * {@code http://egop2builder/EAI_MINT}, a spec navodi {@code https://egopeaitest.mint.hr}
 * bez prefiksa), pa bi ugrađena vrijednost značila da pogrešno podešeno okruženje tiho
 * puca na tuđi eGOP. Bez nje {@link #requireComplete()} pukne na startu.
 *
 * <p>Vrijednosti se ne validiraju bean-validationom jer su prazne dok je integracija
 * ugašena (tada radi {@code EgopClientMock}); {@link #requireComplete()} se poziva
 * isključivo iz {@link EgopConfig}, koji se diže samo uz {@code enabled=true}.
 */
@ConfigurationProperties("hr.infodom.str.integration.egop")
public record EgopProperties(
        boolean enabled,
        String username,
        String password,
        String appDomain,
        String appUsername,
        Integer connectTimeoutMs,
        Integer readTimeoutMs,
        String baseUrl,
        Endpoint mdm,
        Endpoint pismeno,
        Endpoint predmet,
        Endpoint subjekt
) {

    /** Prvi NTLM handshake preko državne mreže zna biti spor, ali ne smije visjeti. */
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;

    /**
     * Urudžbiranje ide sinkrono na request threadu (vidi {@code RnIssuedListener}), a
     * jedan tok radi do ~7 SOAP poziva — zato read timeout mora biti kratak. Što ne
     * stigne, pokupi {@link EgopRetryJob}.
     */
    private static final int DEFAULT_READ_TIMEOUT_MS = 15_000;

    public record Endpoint(String url) {}

    /** Putanje servisa unutar {@code base-url} — jednake na svim okruženjima (spec str. 6-7). */
    private static final String MDM_PATH = "/ServiceMDM.asmx";
    private static final String PISMENO_PATH = "/ServicePismeno.asmx";
    private static final String PREDMET_PATH = "/ServicePredmet.asmx";
    private static final String SUBJEKT_PATH = "/ServiceSubjekt.asmx";

    public EgopProperties {
        connectTimeoutMs = connectTimeoutMs == null ? DEFAULT_CONNECT_TIMEOUT_MS : connectTimeoutMs;
        readTimeoutMs = readTimeoutMs == null ? DEFAULT_READ_TIMEOUT_MS : readTimeoutMs;
        baseUrl = trimTrailingSlash(baseUrl);
        // Pojedinačni URL, ako je postavljen, ima prednost — ostavljeno za slučaj da neko
        // okruženje servise razdvoji po hostovima. Inače se sva četiri izvode iz base-url-a.
        mdm = derive(mdm, baseUrl, MDM_PATH);
        pismeno = derive(pismeno, baseUrl, PISMENO_PATH);
        predmet = derive(predmet, baseUrl, PREDMET_PATH);
        subjekt = derive(subjekt, baseUrl, SUBJEKT_PATH);
    }

    private static String trimTrailingSlash(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /**
     * Prazan string nije isto što i nepostavljena vrijednost samo za Spring — ovdje se oba
     * tretiraju kao „nije zadano". {@code application.properties} sve {@code EGOP_*} ključeve
     * definira s praznim defaultom, pa bi bez ovoga prazan override pobijedio base-url.
     */
    private static Endpoint derive(Endpoint configured, String baseUrl, String path) {
        if (configured != null && configured.url() != null && !configured.url().isBlank()) {
            return configured;
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        return new Endpoint(baseUrl + path);
    }

    /** Oblik {@code DOMENA\korisnik} koji eGOP očekuje u {@code userName} poljima. */
    public String qualifiedAppUsername() {
        return appDomain + "\\" + appUsername;
    }

    /**
     * Praznu vrijednost tretiramo kao grešku konfiguracije: {@code application.properties}
     * definira sve ključeve s praznim defaultom, pa bi bez ove provjere neispravno
     * podešen environment tiho slao {@code "\"} kao korisnika i prazne kredencijale —
     * greška bi se pojavila tek kao odbijanje s eGOP strane.
     */
    public void requireComplete() {
        List<String> missing = new ArrayList<>();
        requireText(missing, "username", username);
        requireText(missing, "password", password);
        requireText(missing, "app-domain", appDomain);
        requireText(missing, "app-username", appUsername);
        requireText(missing, "base-url", baseUrl);
        requireUrl(missing, "mdm.url", mdm);
        requireUrl(missing, "pismeno.url", pismeno);
        requireUrl(missing, "predmet.url", predmet);
        requireUrl(missing, "subjekt.url", subjekt);
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "eGOP integracija je uključena (enabled=true), ali nedostaju obavezni propertyji: "
                            + String.join(", ", missing)
                            + ". Postaviti odgovarajuće EGOP_* environment varijable"
                            + " (adresa se zadaje kroz EGOP_BASE_URL).");
        }
    }

    private static void requireText(List<String> missing, String key, String value) {
        if (value == null || value.isBlank()) {
            missing.add("hr.infodom.str.integration.egop." + key);
        }
    }

    /**
     * Relativna adresa je greška koja se inače vidi tek kao neuspio SOAP poziv: bez base-url-a
     * bi izvedeni endpoint bio {@code /ServiceMDM.asmx}, dakle ne-prazan a neupotrebljiv.
     */
    private static void requireUrl(List<String> missing, String key, Endpoint endpoint) {
        String url = endpoint == null ? null : endpoint.url();
        if (url == null || url.isBlank() || !(url.startsWith("http://") || url.startsWith("https://"))) {
            missing.add("hr.infodom.str.integration.egop." + key + " (nije apsolutan http(s) URL: '"
                    + url + "')");
        }
    }
}
