package com.str.backend.email;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * @param redirectTo ako je postavljen, svaka poruka ide na tu adresu umjesto stvarnom primatelju,
 *                   a stvarni primatelj se upisuje u predmet. Za testne okoline nad stvarnim
 *                   podacima eTurizma. Prazna vrijednost znači da preusmjeravanja nema.
 * @param kpUrl      adresa korisničkog pretinca za poveznicu u poruci iznajmljivaču s pretincem.
 *                   Prazna vrijednost znači da se poveznica ne ispisuje. Adresa koja nije
 *                   apsolutni http(s) URL ruši podizanje: inače bi se greška vidjela tek u poruci.
 */
@ConfigurationProperties("app.mail")
public record MailProperties(
        boolean enabled,
        String from,
        String loginUrl,
        String redirectTo,
        String kpUrl
) {

    public MailProperties {
        redirectTo = redirectTo == null || redirectTo.isBlank() ? null : redirectTo.strip();
        kpUrl = provjeriKpUrl(kpUrl);
    }

    private static String provjeriKpUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String adresa = url.strip();
        try {
            URI uri = new URI(adresa);
            boolean http = "https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme());
            if (http && uri.getHost() != null) {
                return adresa;
            }
        } catch (URISyntaxException e) {
            // Ista poruka kao i za adresu bez sheme ili hosta.
        }
        throw new IllegalArgumentException("app.mail.kp-url mora biti apsolutna http(s) adresa");
    }
}
