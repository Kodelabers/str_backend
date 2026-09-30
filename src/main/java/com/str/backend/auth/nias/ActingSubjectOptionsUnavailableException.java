package com.str.backend.auth.nias;

/**
 * Popis tvrtki za izbornik trenutno se ne može dohvatiti: e-Ovlaštenja nedostupna, usluga nema
 * pristup {@code GetNavigationData}, isključena na okolini ili FINA ne prihvaća sjednicu.
 *
 * <p>Namjerno 503, a ne 401 ni 403: popis frontend dohvaća sam, pri otvaranju izbornika, pa bi 401
 * pokrenuo ponovnu NIAS prijavu koju korisnik nije tražio (i krug, ako FINA uporno vraća isto).
 * Izbornik tada ostaje na upisu OIB-a, a odabir ide punom provjerom.
 */
public class ActingSubjectOptionsUnavailableException extends RuntimeException {

    public ActingSubjectOptionsUnavailableException() {
        super("popis tvrtki iz e-Ovlaštenja trenutno nije dostupan");
    }
}
