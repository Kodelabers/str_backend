package com.str.backend.registries.eovlastenja;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Locale;

/**
 * Konfiguracija provjere zastupanja kroz e-Ovlaštenja
 * ({@code AuthUnionApi/GetAuthorizationUnionPermission}).
 *
 * <ul>
 *   <li><b>Klijentski certifikat</b> za obostrani TLS je aplikacijski certifikat usluge — isti
 *       p12 kojim se potpisuje prema NIAS-u (default iz {@code nias.saml.*}).</li>
 *   <li><b>Truststore</b> je neobavezan <b>dodatak</b> JDK {@code cacerts}-u: serverski TLS
 *       certifikat {@code roapiservis*} ide do javnog Sectigo roota, a dodatak
 *       ({@code classpath:eovlastenja/ca-bundle.crt}, PEM) nosi taj root i FINA Demo CA-ove.
 *       Prima i PKCS12 ({@code .p12}/{@code .pfx}) — tada uz lozinku.</li>
 *   <li><b>Potpisni certifikat</b> je javni certifikat e-Ovlaštenja
 *       ({@code Upravljanje-eOvlastenjimaTst.cer} / {@code eovlastenjaprod.cer}); odgovor se
 *       prihvaća samo ako je potpisan točno njime.</li>
 * </ul>
 *
 * <p>Putanje su na disku ili {@code classpath:…} i nemaju default u {@code application.properties}:
 * uz {@code enabled=true} bez njih aplikacija pada na startu ({@link #requireComplete()}), umjesto
 * da prvi korisnik dobije 503.
 *
 * @param mock parovi (OIB osobe → tvrtka) koje mock proglašava zastupanjem, dok je
 *             {@code enabled=false} — samo bez NIAS-a (local/mock), v. {@link EOvlastenjaConfig}
 */
@ConfigurationProperties("app.eovlastenja")
public record EOvlastenjaProperties(
        boolean enabled,
        String url,
        String keystorePath,
        String keystorePassword,
        String keyAlias,
        String truststorePath,
        String truststorePassword,
        String signerCertPath,
        Integer connectTimeoutMs,
        Integer readTimeoutMs,
        List<MockRepresentation> mock
) {

    private static final String PERMISSION_METHOD = "/GetAuthorizationUnionPermission";
    private static final String NAVIGATION_METHOD = "/GetNavigationData";
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 10_000;

    /** Mock zastupanje: osoba {@code personOib} zastupa tvrtku {@code legalOib}. */
    public record MockRepresentation(String personOib, String legalOib, String legalName, String function) {}

    /**
     * {@code GetNavigationData} je na istom {@code AuthUnionApi} kao {@code url}
     * (test {@code roapiservistst.fina.hr}, prod {@code roapiservis.gov.hr}), pa se ne konfigurira zasebno.
     */
    public String navigationUrl() {
        return url.substring(0, url.length() - PERMISSION_METHOD.length()) + NAVIGATION_METHOD;
    }

    public int effectiveConnectTimeoutMs() {
        return connectTimeoutMs != null ? connectTimeoutMs : DEFAULT_CONNECT_TIMEOUT_MS;
    }

    public int effectiveReadTimeoutMs() {
        return readTimeoutMs != null ? readTimeoutMs : DEFAULT_READ_TIMEOUT_MS;
    }

    /** Konfigurirani mock parovi; nepotpun par ruši start — mock bez naziva tvrtke dao bi RB bez naziva. */
    public List<MockRepresentation> mockOrEmpty() {
        if (mock == null) {
            return List.of();
        }
        for (int i = 0; i < mock.size(); i++) {
            MockRepresentation r = mock.get(i);
            require(r.personOib(), "mock[" + i + "].person-oib");
            require(r.legalOib(), "mock[" + i + "].legal-oib");
            require(r.legalName(), "mock[" + i + "].legal-name");
        }
        return mock;
    }

    void requireComplete() {
        require(url, "url");
        if (!url.endsWith(PERMISSION_METHOD)) {
            throw new IllegalStateException("app.eovlastenja.url mora završavati s " + PERMISSION_METHOD
                    + " (iz nje se izvodi i adresa GetNavigationData)");
        }
        require(keystorePath, "keystore-path");
        require(keystorePassword, "keystore-password");
        require(signerCertPath, "signer-cert-path");
        if (truststoreIsKeyStore()) {
            require(truststorePassword, "truststore-password");
        }
    }

    /** Dodatni CA-ovi kao PKCS12 (s lozinkom); inače su PEM/DER certifikati, bez lozinke. */
    boolean truststoreIsKeyStore() {
        String path = truststorePath == null ? "" : truststorePath.toLowerCase(Locale.ROOT);
        return path.endsWith(".p12") || path.endsWith(".pfx");
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("app.eovlastenja." + name + " nije postavljen");
        }
    }
}
