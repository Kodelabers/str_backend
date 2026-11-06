package com.str.backend.str.verifikacija;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.TransactionTimedOutException;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.List;
import java.util.Locale;

/**
 * Razvrstavanje neuspjelog pokušaja i siguran opis za {@code last_error} / log. Čiste funkcije.
 *
 * <ul>
 *   <li>{@link Vrsta#BEZ_PRAVA} — {@code permission denied} (PostgreSQL {@code 42501}, H2 {@code 90096}):
 *       nedostaje GRANT; pokušaji se ne troše, redak čeka grant.</li>
 *   <li>{@link Vrsta#INFRASTRUKTURA} — istek vremena, zaključavanje, veza, transakcijski sustav: pada
 *       sve, pa retry job prekida seriju umjesto da svaki redak čeka svoj timeout.</li>
 *   <li>{@link Vrsta#PODACI} — sve ostalo (npr. ograničenje na jednom retku): obični neuspjeh retka.</li>
 * </ul>
 */
final class VerifikacijaGreska {

    enum Vrsta { BEZ_PRAVA, INFRASTRUKTURA, PODACI }

    /** Najdulji opis — stane u {@code last_error}. */
    static final int MAX_OPIS = 400;

    private static final List<String> BEZ_PRAVA_STANJA = List.of("42501", "90096");

    /** Veza (08…), otkazana naredba/istek (57014, HYT00, HY008), zaključavanje (55P03, 40001, 40P01), resursi (53…), gašenje (57P…). */
    private static final List<String> INFRASTRUKTURA_PREFIKSI =
            List.of("08", "57014", "57P", "55P03", "40001", "40P01", "53", "HYT00", "HY008");

    /** Dijelovi poruke iza kojih stoje vrijednosti iz retka — ne idu u {@code last_error} ni u log. */
    private static final List<String> REZ = List.of("Detail:", "Detalji:", "Failing row contains", "; SQL statement:", "\n");

    private VerifikacijaGreska() {
    }

    /**
     * Razvrstava po cijelom lancu uzroka. Neuspjeli rollback koji je {@link EturizamVerifikacijaWriter}
     * dodao kao {@code suppressed} (veza je pala nakon greške) čini neuspjeh infrastrukturnim, osim kad je
     * izvorna greška nedostatak prava.
     */
    static Vrsta vrsta(Throwable e) {
        Vrsta vrsta = vrstaLanca(e);
        if (vrsta == Vrsta.PODACI) {
            for (Throwable s : e.getSuppressed()) {
                if (vrstaLanca(s) != Vrsta.PODACI) {
                    return Vrsta.INFRASTRUKTURA;
                }
            }
        }
        return vrsta;
    }

    private static Vrsta vrstaLanca(Throwable e) {
        boolean infrastruktura = false;
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                String stanje = sql.getSQLState().toUpperCase(Locale.ROOT);
                if (BEZ_PRAVA_STANJA.contains(stanje)) {
                    return Vrsta.BEZ_PRAVA;
                }
                if (INFRASTRUKTURA_PREFIKSI.stream().anyMatch(stanje::startsWith)) {
                    infrastruktura = true;
                }
            }
            if (t instanceof TransactionTimedOutException || t instanceof QueryTimeoutException
                    || t instanceof PessimisticLockingFailureException
                    || t instanceof TransientDataAccessResourceException
                    || t instanceof DataAccessResourceFailureException
                    || t instanceof CannotCreateTransactionException || t instanceof TransactionSystemException
                    || t instanceof SQLTransientException || t instanceof SQLRecoverableException
                    || t instanceof SQLNonTransientConnectionException) {
                infrastruktura = true;
            }
        }
        return infrastruktura ? Vrsta.INFRASTRUKTURA : Vrsta.PODACI;
    }

    /**
     * Najdublji uzrok (obično SQL poruka), bez dijela s vrijednostima retka ({@code Detail:},
     * {@code Failing row contains}, SQL naredba kod H2) i skraćen na {@value #MAX_OPIS} znakova.
     */
    static String opis(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String poruka = root.getMessage();
        if (poruka != null) {
            for (String rez : REZ) {
                int i = poruka.indexOf(rez);
                if (i >= 0) {
                    poruka = poruka.substring(0, i);
                }
            }
            poruka = poruka.strip();
        }
        String opis = root.getClass().getSimpleName() + (poruka == null || poruka.isEmpty() ? "" : ": " + poruka);
        return opis.length() <= MAX_OPIS ? opis : opis.substring(0, MAX_OPIS);
    }
}
