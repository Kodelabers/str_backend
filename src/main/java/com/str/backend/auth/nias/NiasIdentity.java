package com.str.backend.auth.nias;

/**
 * Identitetski podaci izvučeni iz NIAS SAML assertiona. Testni NIAS šalje atribute
 * {@code ime, prezime, oib} (+ token/meta: {@code oznaka_drzave_eid, tid, nav_token}).
 * Rolu ni email NIAS NE šalje — pa se ovdje ne modeliraju.
 */
public record NiasIdentity(
        String oib,
        String firstName,
        String lastName
) {}
