package com.str.backend.registries.oib;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Konfiguracija poziva OIB sustava ({@code str-internal-api/pretraga-registra/oib}).
 *
 * <p>{@code base-url} namjerno <b>nema default</b> — isti razlog kao kod eGOP-a: okoline se
 * razlikuju po hostu, pa bi ugrađena vrijednost značila da pogrešno podešena okolina tiho zove
 * tuđi registar. Uz {@code enabled=true} bez adrese aplikacija pada na startu
 * ({@link #requireComplete()}).
 *
 * <p>Servis nema autentikacije — zaštićen je mrežom.
 */
@ConfigurationProperties("app.oib-registry")
public record OibRegistryProperties(
        boolean enabled,
        String baseUrl,
        Integer connectTimeoutMs,
        Integer readTimeoutMs
) {

    /** Poziv ide sinkrono na requestu za izdavanje RB-a i na prefillu forme — ne smije visjeti. */
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 3_000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 5_000;

    public int effectiveConnectTimeoutMs() {
        return connectTimeoutMs != null ? connectTimeoutMs : DEFAULT_CONNECT_TIMEOUT_MS;
    }

    public int effectiveReadTimeoutMs() {
        return readTimeoutMs != null ? readTimeoutMs : DEFAULT_READ_TIMEOUT_MS;
    }

    void requireComplete() {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(
                    "app.oib-registry.enabled=true, ali app.oib-registry.base-url nije postavljen");
        }
    }
}
