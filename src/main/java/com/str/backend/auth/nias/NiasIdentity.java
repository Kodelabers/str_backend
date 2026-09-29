package com.str.backend.auth.nias;

/**
 * Identitetski podaci izvučeni iz NIAS SAML assertiona. Testni NIAS šalje atribute
 * {@code ime, prezime, oib} (+ token/meta: {@code oznaka_drzave_eid, tid, nav_token}).
 * Rolu ni email NIAS NE šalje — pa se ovdje ne modeliraju.
 *
 * @param sesijaId identifikator NIAS sjednice ({@code sesija_id}); obavezan za provjeru
 *                 e-Ovlaštenja, a NIAS ga šalje samo usluzi registriranoj i za autorizaciju —
 *                 zato smije biti {@code null}
 * @param tid      identifikator korisnika u NIAS-u; {@code null} kad ga assertion nema
 */
public record NiasIdentity(
        String oib,
        String firstName,
        String lastName,
        String sesijaId,
        String tid
) {

    /** Identitet bez NIAS sjednice — local/mock i testovi. */
    public NiasIdentity(String oib, String firstName, String lastName) {
        this(oib, firstName, lastName, null, null);
    }
}
