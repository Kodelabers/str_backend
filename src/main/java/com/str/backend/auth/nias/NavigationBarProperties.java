package com.str.backend.auth.nias;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

/**
 * FINA-ina jedinstvena navigacijska traka e-Građani ({@code e_gradani.aspx}). Traka u pregledniku
 * prikazuje subjekte koje osoba smije zastupati i odabir vraća na naš {@code change_entity_url}
 * ({@link NavigationBarService}).
 *
 * @param enabled   traka se nudi frontendu; bez toga {@code GET /api/nias/navigation-bar} vraća 204
 * @param scriptUrl adresa skripte po NIAS okolini — test {@code https://eusluge-nav-test.gov.hr/e_gradani.aspx},
 *                  produkcija {@code https://eusluge-nav.gov.hr/e_gradani.aspx}; mora biti ista
 *                  okolina kao NIAS, inače traka ne poznaje {@code navToken} i prikaže „Prijava"
 * @param publicBaseUrl javna adresa aplikacije (jedan origin: frontend nginx prosljeđuje
 *                  {@code /api} i {@code /saml2} backendu) — iz nje se slažu {@code login_url},
 *                  {@code logout_url} i {@code change_entity_url}
 * @param returnPath stranica frontenda na koju se korisnik vraća nakon odabira u traci
 */
@ConfigurationProperties("app.nias.navigation-bar")
public record NavigationBarProperties(
        boolean enabled,
        String scriptUrl,
        String publicBaseUrl,
        String returnPath
) {

    /** Test (NIAS test) i produkcija. */
    static final Set<String> ALLOWED_HOSTS = Set.of("eusluge-nav-test.gov.hr", "eusluge-nav.gov.hr");

    public NavigationBarProperties {
        publicBaseUrl = stripTrailingSlash(publicBaseUrl);
        returnPath = returnPath == null || returnPath.isBlank() ? "/existing-objects" : returnPath;
    }

    /** Uključena traka bez adresa ruši start — inače bi prvi korisnik dobio traku koja ne radi. */
    void requireComplete() {
        if (!enabled) {
            return;
        }
        if (!isFinaScript(scriptUrl)) {
            throw new IllegalStateException("app.nias.navigation-bar.script-url mora biti https adresa e_gradani.aspx "
                    + "na " + ALLOWED_HOSTS + " — skripta se izvršava s punim ovlastima naše stranice");
        }
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
            throw new IllegalStateException("app.nias.navigation-bar.public-base-url nije postavljen");
        }
    }

    /** Samo FINA-ina traka: pogrešna vrijednost inače bi u stranicu umetnula proizvoljan JavaScript. */
    static boolean isFinaScript(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            URI uri = new URI(url.trim());
            return "https".equals(uri.getScheme()) && uri.getUserInfo() == null && uri.getPort() == -1
                    && ALLOWED_HOSTS.contains(uri.getHost());
        } catch (URISyntaxException e) {
            return false;
        }
    }

    String returnUrl() {
        return publicBaseUrl + returnPath;
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) {
            return null;
        }
        String t = url.trim();
        return t.endsWith("/") ? t.substring(0, t.length() - 1) : t;
    }
}
