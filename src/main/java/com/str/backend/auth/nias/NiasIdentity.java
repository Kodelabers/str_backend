package com.str.backend.auth.nias;

/**
 * Identitetski podaci izvučeni iz NIAS SAML assertiona. {@code oib} je uvijek prisutan;
 * ostala polja su {@code null} ako ih assertion ne sadrži (ili im naziv atributa nije potvrđen).
 */
public record NiasIdentity(
        String oib,
        String firstName,
        String lastName,
        String role,
        String email
) {}
