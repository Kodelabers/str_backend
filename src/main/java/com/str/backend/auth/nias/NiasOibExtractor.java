package com.str.backend.auth.nias;

import org.springframework.security.core.Authentication;
import org.springframework.security.saml2.provider.service.authentication.DefaultSaml2AuthenticatedPrincipal;
import org.springframework.security.saml2.provider.service.authentication.Saml2Authentication;

import java.util.List;
import java.util.Optional;

public final class NiasOibExtractor {

    private NiasOibExtractor() {}

    public static Optional<String> extractOib(Authentication auth) {
        if (!(auth instanceof Saml2Authentication samlAuth)) {
            return Optional.empty();
        }
        if (!(samlAuth.getPrincipal() instanceof DefaultSaml2AuthenticatedPrincipal principal)) {
            return Optional.empty();
        }
        List<Object> values = principal.getAttributes().get("oib");
        if (values == null || values.isEmpty()) {
            return Optional.empty();
        }
        Object val = values.get(0);
        return Optional.ofNullable(val instanceof String s ? s : String.valueOf(val));
    }
}
