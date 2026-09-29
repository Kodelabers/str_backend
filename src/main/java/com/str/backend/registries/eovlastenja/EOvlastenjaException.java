package com.str.backend.registries.eovlastenja;

/**
 * Provjera zastupanja nije prošla iz razloga koji korisniku treba objasniti — za razliku od
 * kvara (nedostupan servis, neispravan potpis), koji je
 * {@link com.str.backend.exception.ExternalRegistryException} i 503.
 *
 * <p>Razlozi su izvedeni iz šifarnika e-Ovlaštenja („Popis grešaka-rest (V2)").
 */
public class EOvlastenjaException extends RuntimeException {

    public enum Reason {
        /** 200–203: NIAS sjednica istekla, ne pripada korisniku ili nije poslana — ponovna prijava. */
        SESSION,
        /** Nema zastupanja, osoba nije u e-Ovlaštenjima (400) ili nije dala privolu (401). */
        NOT_REPRESENTATIVE,
        /** 500: poslovni subjekt po traženom JIPS-u ne postoji. */
        SUBJECT_NOT_FOUND
    }

    private final Reason reason;
    private final String code;

    public EOvlastenjaException(Reason reason, String code, String message) {
        super(message);
        this.reason = reason;
        this.code = code;
    }

    public Reason reason() {
        return reason;
    }

    /** Šifra greške e-Ovlaštenja; {@code null} kad greške nema, a zastupanja ipak nema. */
    public String code() {
        return code;
    }
}
