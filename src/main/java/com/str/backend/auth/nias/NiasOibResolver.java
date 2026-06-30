package com.str.backend.auth.nias;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Resolves the current NIAS user's OIB:
 * 1) real NIAS SAML2 authentication via {@link NiasOibExtractor}, OR
 * 2) configured mock OIB ({@code nias.mock.fixed-oib}) when the property is set
 *    on local/mock Spring profiles. The mock OIB is seeded in Liquibase 048
 *    with three ACTIVE RNs so the "Moji registracijski brojevi" view has content.
 *
 * Returns {@link Optional#empty()} when neither path applies (dev/prod without
 * a real NIAS session) — controller should respond 401.
 */
@Component
public class NiasOibResolver {

    private final String mockFixedOib;

    public NiasOibResolver(@Value("${nias.mock.fixed-oib:}") String mockFixedOib) {
        this.mockFixedOib = mockFixedOib == null || mockFixedOib.isBlank() ? null : mockFixedOib.trim();
    }

    public Optional<String> resolve(Authentication authentication) {
        Optional<String> real = NiasOibExtractor.extractOib(authentication);
        if (real.isPresent()) return real;
        return Optional.ofNullable(mockFixedOib);
    }
}
