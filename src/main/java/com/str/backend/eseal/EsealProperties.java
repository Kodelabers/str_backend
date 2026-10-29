package com.str.backend.eseal;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Konfiguracija STR eSeal servisa ({@code POST /api/v1/seals/pdf}) — kvalificirani elektronički
 * pečat izlaznih akata (čl. 98. st. 8 ZUP-a).
 *
 * <p>{@code base-url} namjerno <b>nema default</b>: pogrešno podešena okolina ne smije tiho pečatirati
 * na tuđem projektu. Uz {@code enabled=true} bez adrese, ključa ili projekta aplikacija pada na startu
 * ({@link #requireComplete()}).
 *
 * <p>Read timeout je 90 s jer servis sam ograničava pečatiranje na 60 s, a specifikacija klijentu
 * traži barem 90 s za E1. Kraći timeout bi prekidao pečatiranja koja servis još uspješno dovršava.
 *
 * <p>{@code base-url} mora biti {@code https://}, osim prema vlastitom stroju ili uz izričito
 * {@code allow-http=true}: uz poziv idu API ključ i akt s osobnim podacima. Na CDU testu servis je na
 * internoj adresi dostupan samo preko HTTP-a (29.10.2026.) — zato prekidač, a ne tiho popuštanje.
 */
@ConfigurationProperties("str.eseal")
public record EsealProperties(
        // Boolean, ne boolean: prazna env varijabla daje null (isključeno), a ne grešku bindanja na startu.
        Boolean enabled,
        String baseUrl,
        String apiKey,
        /** Šalje se uvijek u headeru {@code Project}, i kad ga ključ ne bi trebao — eksplicitno je sigurnije. */
        String project,
        Integer connectTimeoutMs,
        Integer readTimeoutMs,
        /** Dopušta {@code http://} prema hostu koji nije lokalni — samo za internu mrežu, nikad za produkciju. */
        Boolean allowHttp
) {

    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 90_000;
    /** Jedini hostovi kojima {@code base-url} smije biti {@code http://} (lokalni razvoj). */
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    public int effectiveConnectTimeoutMs() {
        return connectTimeoutMs != null ? connectTimeoutMs : DEFAULT_CONNECT_TIMEOUT_MS;
    }

    public int effectiveReadTimeoutMs() {
        return readTimeoutMs != null ? readTimeoutMs : DEFAULT_READ_TIMEOUT_MS;
    }

    /** {@code http://} prema hostu koji nije lokalni stroj — dopušteno samo uz {@code allow-http}. */
    boolean isInsecureHttp() {
        URI uri = parse();
        return "http".equalsIgnoreCase(uri.getScheme()) && !LOOPBACK_HOSTS.contains(uri.getHost());
    }

    void requireComplete() {
        List<String> missing = new ArrayList<>();
        if (isBlank(baseUrl)) {
            missing.add("str.eseal.base-url (ESEAL_BASE_URL)");
        }
        if (isBlank(apiKey)) {
            missing.add("str.eseal.api-key (ESEAL_API_KEY)");
        }
        if (isBlank(project)) {
            missing.add("str.eseal.project (ESEAL_PROJECT)");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("eSeal je uključen (str.eseal.enabled=true), ali nisu postavljeni: "
                    + String.join(", ", missing));
        }
        // Uz poziv ide API ključ i cijeli akt s osobnim podacima stranke — preko plain HTTP-a ne
        // smije, osim prema vlastitom stroju ili uz izričit allow-http. URL nije tajna, pa poruka
        // navodi što je stiglo: inače se iz loga ne vidi je li kriva shema, host ili navodnici.
        URI uri = parse();
        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
            throw new IllegalStateException("str.eseal.base-url mora počinjati s https:// (dobiveno: shema="
                    + scheme + "; navodnici u .env-u ili nedostaje shema?)");
        }
        if (uri.getHost() == null) {
            throw new IllegalStateException("str.eseal.base-url: host nije prepoznat (dobiveno: "
                    + baseUrl.trim() + "; podvlaka u imenu hosta?)");
        }
        if (isInsecureHttp() && !Boolean.TRUE.equals(allowHttp)) {
            throw new IllegalStateException("str.eseal.base-url je http:// prema " + uri.getHost()
                    + " — mora biti https:// (uz poziv idu API ključ i akt s osobnim podacima). Samo za"
                    + " internu mrežu: ESEAL_ALLOW_HTTP=true");
        }
    }

    private URI parse() {
        try {
            return new URI(baseUrl.trim());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("str.eseal.base-url nije ispravan URL (navodnici u .env-u?): "
                    + e.getMessage(), e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
