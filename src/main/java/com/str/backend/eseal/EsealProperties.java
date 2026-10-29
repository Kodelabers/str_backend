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
        Integer readTimeoutMs
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
        // smije, osim prema vlastitom stroju.
        URI uri;
        try {
            uri = new URI(baseUrl.trim());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("str.eseal.base-url nije ispravan URL", e);
        }
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        boolean loopback = "http".equalsIgnoreCase(uri.getScheme()) && LOOPBACK_HOSTS.contains(uri.getHost());
        if (uri.getHost() == null || !(https || loopback)) {
            throw new IllegalStateException(
                    "str.eseal.base-url mora biti https:// (uz poziv idu API ključ i akt s osobnim podacima)");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
