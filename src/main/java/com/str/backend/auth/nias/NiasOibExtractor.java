package com.str.backend.auth.nias;

import org.springframework.security.core.Authentication;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal;
import org.springframework.security.saml2.provider.service.authentication.Saml2Authentication;

import java.util.Optional;

public final class NiasOibExtractor {

    // NIAS assertion attribute nazivi (potvrđeni capture-om na CDU 2026-07-14).
    // Assertion sadrži: ime, prezime, oib, oznaka_drzave_eid, tid, nav_token.
    // Rolu i email NIAS NE šalje.
    private static final String ATTR_OIB = "oib";
    private static final String ATTR_FIRST_NAME = "ime";
    private static final String ATTR_LAST_NAME = "prezime";
    // Šalje se samo usluzi registriranoj i za autorizaciju (e-Ovlaštenja) — v. NiasIdentity.
    private static final String ATTR_SESIJA_ID = "sesija_id";
    private static final String ATTR_TID = "tid";

    private NiasOibExtractor() {}

    public static Optional<String> extractOib(Authentication auth) {
        return principalOf(auth).map(p -> firstAttr(p, ATTR_OIB));
    }

    /**
     * Izvlači identitetske atribute iz NIAS SAML principala. Vraća {@link Optional#empty()}
     * ako nije NIAS autentikacija ili u assertionu nema OIB-a; ime/prezime mogu biti {@code null}.
     */
    public static Optional<NiasIdentity> extractIdentity(Authentication auth) {
        Optional<Saml2AuthenticatedPrincipal> maybePrincipal = principalOf(auth);
        if (maybePrincipal.isEmpty()) {
            return Optional.empty();
        }
        Saml2AuthenticatedPrincipal principal = maybePrincipal.get();
        String oib = firstAttr(principal, ATTR_OIB);
        if (oib == null) {
            return Optional.empty();
        }
        return Optional.of(new NiasIdentity(
                oib,
                firstAttr(principal, ATTR_FIRST_NAME),
                firstAttr(principal, ATTR_LAST_NAME),
                trimmed(firstAttr(principal, ATTR_SESIJA_ID)),
                trimmed(firstAttr(principal, ATTR_TID))));
    }

    /** FINA primjeri {@code sesija_id} šalju s okolnim razmacima i novim redovima. */
    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }

    private static Optional<Saml2AuthenticatedPrincipal> principalOf(Authentication auth) {
        if (auth instanceof Saml2Authentication samlAuth
                && samlAuth.getPrincipal() instanceof Saml2AuthenticatedPrincipal principal) {
            return Optional.of(principal);
        }
        return Optional.empty();
    }

    private static String firstAttr(Saml2AuthenticatedPrincipal principal, String name) {
        Object val = principal.getFirstAttribute(name);
        if (val == null) {
            return null;
        }
        return val instanceof String s ? s : String.valueOf(val);
    }
}
