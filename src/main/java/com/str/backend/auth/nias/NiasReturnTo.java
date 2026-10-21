package com.str.backend.auth.nias;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

/**
 * Povratak na originalni URL nakon NIAS prijave.
 *
 * <p>FE korisnika bez STR sesije šalje na {@code /saml2/authenticate/nias?returnTo=/put?upit}.
 * Uz postojeću NIAS SSO sjednicu (npr. dolazak iz TuStarta) NIAS se vraća bez unosa podataka, a
 * success handler korisnika vodi na taj put umjesto na fiksni {@code nias.saml.success-redirect-url}.
 * Put se pamti uz spremljeni AuthnRequest — u sesiji ({@link SessionReturnToAuthenticationRequestRepository})
 * ili u {@code str_rn.saml_auth_request} ({@link DatabaseSaml2AuthenticationRequestRepository}) — i na
 * ACS-u, kad Spring zahtjev potroši, prelazi u request atribut {@link #REQUEST_ATTR}.
 *
 * <p>Prihvaća se samo relativan put na našem FE-u: odredište je uvijek origin success URL-a + put,
 * pa preusmjerenje ne može napustiti FE (nema open-redirecta). Neispravna vrijednost se tiho
 * ignorira i prijava završava na fiksnom URL-u, kao i bez parametra.
 */
public final class NiasReturnTo {

    private static final Logger log = LoggerFactory.getLogger(NiasReturnTo.class);

    public static final String PARAM = "returnTo";
    static final String SESSION_KEY = NiasReturnTo.class.getName() + ".RETURN_TO";
    static final String REQUEST_ATTR = NiasReturnTo.class.getName() + ".REQUEST";

    /** Isto kao stupac {@code str_rn.saml_auth_request.return_to} (changeset 141). */
    static final int MAX_LENGTH = 2048;

    private NiasReturnTo() {
    }

    /** Relativan put s upitom, ili prazno ako vrijednost nije sigurna za preusmjerenje. */
    public static Optional<String> sanitize(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        // "//host" i "/\host" preglednici čitaju kao URL bez sheme, tj. drugi host.
        if (!raw.startsWith("/") || raw.startsWith("//") || raw.indexOf('\\') >= 0) {
            return Optional.empty();
        }
        // Samo ispisivi ASCII: kontrolni znakovi otvaraju header injection, a ne-ASCII Tomcat u
        // Location piše kao ISO-8859-1 i preusmjerenje se pokvari. FE šalje pathname + search,
        // koji preglednik već percent-encodira.
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 0x20 || c > 0x7e) {
                return Optional.empty();
            }
        }
        try {
            URI uri = new URI(raw);
            if (uri.getScheme() != null || uri.getRawAuthority() != null) {
                return Optional.empty();
            }
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        return Optional.of(raw);
    }

    /**
     * {@code ?returnTo=} iz zahtjeva za prijavu, ako je siguran. Odbijena vrijednost se logira
     * (duljina, ne sadržaj: upit može nositi podatke iz forme), da se FE greška ne vidi samo kao
     * „završio sam na početnoj".
     */
    static Optional<String> fromRequest(HttpServletRequest request) {
        String raw = request.getParameter(PARAM);
        if (raw == null) {
            return Optional.empty();
        }
        Optional<String> safe = sanitize(raw);
        if (safe.isEmpty()) {
            log.warn("nias_return_to_rejected length={} — prijava završava na success-redirect-url", raw.length());
        }
        return safe;
    }

    /** Origin (shema, host, port) success URL-a + put. */
    static String target(String successRedirectUrl, String returnTo) {
        URI success = URI.create(successRedirectUrl);
        return success.getScheme() + "://" + success.getRawAuthority() + returnTo;
    }

    /** Kamo success handler šalje korisnika: na zapamćeni put ako ga ima, inače na fiksni URL. */
    static String redirectTarget(HttpServletRequest request, String successRedirectUrl) {
        boolean remembered = request.getAttribute(REQUEST_ATTR) instanceof String;
        log.info("nias_login_redirect return_to={}", remembered);
        return remembered
                ? target(successRedirectUrl, (String) request.getAttribute(REQUEST_ATTR))
                : successRedirectUrl;
    }
}
