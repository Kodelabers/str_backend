package com.str.backend.auth.nias;

import org.springframework.security.core.Authentication;
import org.springframework.security.saml2.provider.service.authentication.DefaultSaml2AuthenticatedPrincipal;
import org.springframework.security.saml2.provider.service.authentication.Saml2Authentication;

import java.util.List;
import java.util.Optional;

public final class NiasOibExtractor {

    // NIAS assertion attribute nazivi.
    // "oib" je potvrđen. ime/prezime/rola/email su PRIVREMENI — potvrditi capture-om
    // (Korak 0 plana: privremeni log principal.getAttributes() u NiasSamlConfig na CDU)
    // i po potrebi ispraviti ovdje.
    private static final String ATTR_OIB = "oib";
    private static final String ATTR_FIRST_NAME = "ime";
    private static final String ATTR_LAST_NAME = "prezime";
    private static final String ATTR_ROLE = "rola";
    private static final String ATTR_EMAIL = "email";

    private NiasOibExtractor() {}

    public static Optional<String> extractOib(Authentication auth) {
        return principalOf(auth).map(p -> firstAttr(p, ATTR_OIB));
    }

    /**
     * Izvlači sve identitetske atribute iz NIAS SAML principala. Vraća {@link Optional#empty()}
     * ako nije NIAS autentikacija ili u assertionu nema OIB-a; ostala polja mogu biti {@code null}.
     */
    public static Optional<NiasIdentity> extractIdentity(Authentication auth) {
        Optional<DefaultSaml2AuthenticatedPrincipal> maybePrincipal = principalOf(auth);
        if (maybePrincipal.isEmpty()) {
            return Optional.empty();
        }
        DefaultSaml2AuthenticatedPrincipal principal = maybePrincipal.get();
        String oib = firstAttr(principal, ATTR_OIB);
        if (oib == null) {
            return Optional.empty();
        }
        return Optional.of(new NiasIdentity(
                oib,
                firstAttr(principal, ATTR_FIRST_NAME),
                firstAttr(principal, ATTR_LAST_NAME),
                firstAttr(principal, ATTR_ROLE),
                firstAttr(principal, ATTR_EMAIL)));
    }

    private static Optional<DefaultSaml2AuthenticatedPrincipal> principalOf(Authentication auth) {
        if (!(auth instanceof Saml2Authentication samlAuth)) {
            return Optional.empty();
        }
        if (!(samlAuth.getPrincipal() instanceof DefaultSaml2AuthenticatedPrincipal principal)) {
            return Optional.empty();
        }
        return Optional.of(principal);
    }

    private static String firstAttr(DefaultSaml2AuthenticatedPrincipal principal, String name) {
        List<Object> values = principal.getAttributes().get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        Object val = values.get(0);
        if (val == null) {
            return null;
        }
        return val instanceof String s ? s : String.valueOf(val);
    }
}
