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

    public EgopProperties {
        connectTimeoutMs = connectTimeoutMs == null ? DEFAULT_CONNECT_TIMEOUT_MS : connectTimeoutMs;
        readTimeoutMs = readTimeoutMs == null ? DEFAULT_READ_TIMEOUT_MS : readTimeoutMs;
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
        requireUrl(missing, "mdm.url", mdm);
        requireUrl(missing, "pismeno.url", pismeno);
        requireUrl(missing, "predmet.url", predmet);
        requireUrl(missing, "subjekt.url", subjekt);
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "eGOP integracija je uključena (enabled=true), ali nedostaju obavezni propertyji: "
                            + String.join(", ", missing)
                            + ". Postaviti odgovarajuće EGOP_* environment varijable.");
        }
    }

    private static void requireText(List<String> missing, String key, String value) {
        if (value == null || value.isBlank()) {
            missing.add("hr.infodom.str.integration.egop." + key);
        }
    }

    private static void requireUrl(List<String> missing, String key, Endpoint endpoint) {
        requireText(missing, key, endpoint == null ? null : endpoint.url());
    }
}
