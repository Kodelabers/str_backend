package com.str.backend.registries.oib;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

/**
 * Konfiguracija poziva OIB sustava ({@code str-internal-api/pretraga-registra/oib/FO|PO/{oib}}).
 *
 * <p>Dvije zastavice, jer su to dva toka koji se uključuju neovisno:
 * <ul>
 *   <li>{@code enabled} — fizička osoba u svoje ime ({@code lessor.SubjectRegistry});</li>
 *   <li>{@code legal-entity-enabled} — u ime tvrtke: sjedište tvrtke i zastupnik
 *       ({@code lessor.LegalEntityRegistry}).</li>
 * </ul>
 *
 * <p>{@code base-url} namjerno <b>nema default</b> — isti razlog kao kod eGOP-a: okoline se
 * razlikuju po hostu, pa bi ugrađena vrijednost značila da pogrešno podešena okolina tiho zove
 * tuđi registar. Uz bilo koju uključenu zastavicu bez adrese aplikacija pada na startu
 * ({@link #requireComplete()}).
 *
 * <p>Servis je iza NIAS-a; poziv se autorizira korisnikovom sesijom iz zajedničke tablice
 * ({@code SharedSessionCookieInterceptor}), pa {@code base-url} mora biti {@code https://}.
 */
@ConfigurationProperties("app.oib-registry")
public record OibRegistryProperties(
        // Boolean, ne boolean: prazna env varijabla daje null (isključeno), a ne grešku bindanja na startu.
        Boolean enabled,
        Boolean legalEntityEnabled,
        String baseUrl,
        Integer connectTimeoutMs,
        Integer readTimeoutMs
) {

    /** Poziv ide sinkrono na requestu za izdavanje RB-a i na prefillu forme — ne smije visjeti. */
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 3_000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 5_000;
    /** Jedini hostovi kojima {@code base-url} smije biti {@code http://} (lokalni razvoj). */
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    public int effectiveConnectTimeoutMs() {
        return connectTimeoutMs != null ? connectTimeoutMs : DEFAULT_CONNECT_TIMEOUT_MS;
    }

    public int effectiveReadTimeoutMs() {
        return readTimeoutMs != null ? readTimeoutMs : DEFAULT_READ_TIMEOUT_MS;
    }

    void requireComplete() {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(
                    "OIB sustav je uključen (app.oib-registry.enabled ili legal-entity-enabled), "
                            + "ali app.oib-registry.base-url nije postavljen");
        }
        // Poziv nosi korisnikov sesijski cookie (SharedSessionCookieInterceptor), a on vrijedi
        // kao lozinka: preko plain HTTP-a ne smije, osim prema vlastitom stroju.
        URI uri;
        try {
            uri = new URI(baseUrl.trim());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("app.oib-registry.base-url nije ispravan URL", e);
        }
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        boolean loopback = "http".equalsIgnoreCase(uri.getScheme()) && LOOPBACK_HOSTS.contains(uri.getHost());
        if (uri.getHost() == null || !(https || loopback)) {
            throw new IllegalStateException(
                    "app.oib-registry.base-url mora biti https:// (uz poziv ide korisnikova sesija)");
        }
    }
}
