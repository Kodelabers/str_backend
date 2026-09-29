package com.str.backend.auth.nias;

/**
 * Zahtjev je pripremljen za jednog vlasnika, a sesija u međuvremenu djeluje za drugog — npr. obrazac
 * otvoren u svoje ime, a u drugom prozoru odabrana tvrtka. Radnja se ne izvodi (409), umjesto da se
 * tiho izvrši za onoga tko je trenutno u sesiji.
 *
 * @param current OIB tvrtke u čije ime sesija sada djeluje; {@code null} kad djeluje u svoje ime
 */
public class ActingSubjectChangedException extends RuntimeException {

    private final String current;

    public ActingSubjectChangedException(String current) {
        super("subjekt u čije ime se djeluje promijenjen je u međuvremenu");
        this.current = current;
    }

    public String current() {
        return current;
    }
}
