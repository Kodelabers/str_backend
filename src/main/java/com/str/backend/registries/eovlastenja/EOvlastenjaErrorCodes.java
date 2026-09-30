package com.str.backend.registries.eovlastenja;

import com.str.backend.exception.ExternalRegistryException;
import com.str.backend.registries.eovlastenja.EOvlastenjaException.Reason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.Set;

/**
 * Šifre grešaka e-Ovlaštenja (šifarnik „Popis grešaka-rest (V2)") i njihovo značenje za nas. Iste
 * šifre dolaze u potpisanom XML-u ({@code Errors}) i u nepotpisanom JSON-u uz HTTP 4xx
 * ({@code {"Code":"100",…}}), pa je tablica na jednom mjestu.
 *
 * <p>Sve što tablica vraća je <b>odbijanje</b> — nijedna šifra ne može dati pravo. Zato se smije
 * primijeniti i na nepotpisan odgovor: podmetnuta šifra može samo uskratiti, ne i odobriti.
 */
final class EOvlastenjaErrorCodes {

    private static final Logger log = LoggerFactory.getLogger(EOvlastenjaErrorCodes.class);

    private static final Set<String> SESSION = Set.of("200", "201", "202", "203");
    private static final Set<String> NOT_REPRESENTATIVE = Set.of("400", "401");
    private static final String SUBJECT_NOT_FOUND = "500";
    /** 100 = pristup metodi nije dozvoljen, 402 = modul e-Zastupanja isključen — registracija usluge. */
    private static final Set<String> CONFIGURATION = Set.of("100", "402");

    private EOvlastenjaErrorCodes() {}

    /**
     * Odbijanje za šifru, ili prazno za šifru koja ne odbija (403/404 — e-Punomoći isključene,
     * preskočeni zapisi — i nepoznate). Poruka FINA-e ide u iznimku, ali ne u log osim za
     * šifre registracije.
     */
    static Optional<RuntimeException> rejection(String code, String message) {
        if (code == null) {
            return Optional.empty();
        }
        if (SESSION.contains(code)) {
            return Optional.of(new EOvlastenjaException(Reason.SESSION, code, message));
        }
        if (NOT_REPRESENTATIVE.contains(code)) {
            return Optional.of(new EOvlastenjaException(Reason.NOT_REPRESENTATIVE, code, message));
        }
        if (SUBJECT_NOT_FOUND.equals(code)) {
            return Optional.of(new EOvlastenjaException(Reason.SUBJECT_NOT_FOUND, code, message));
        }
        if (CONFIGURATION.contains(code)) {
            log.error("eovlastenja_config_error code={} message={}", code, message);
            return Optional.of(new ExternalRegistryException(EOvlastenjaResponseParser.REGISTRY,
                    "e-Ovlaštenja odbijaju uslugu (šifra " + code + ")"));
        }
        return Optional.empty();
    }
}
