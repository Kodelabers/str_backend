package com.str.backend.auth.nias;

import com.str.backend.common.Oib;
import com.str.backend.exception.BusinessException;
import com.str.backend.registries.eovlastenja.EOvlastenjaClient;
import com.str.backend.registries.eovlastenja.EOvlastenjaException;
import com.str.backend.registries.eovlastenja.Zastupanje;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Odabir pravne osobe u čije ime NIAS korisnik djeluje (e-Zastupanja).
 *
 * <p>Subjekt se sprema u sesiju <b>tek</b> nakon što ga e-Ovlaštenja potvrde; OIB tvrtke iz
 * zahtjeva je samo prijedlog. Vrijedi samo za osobu koja ga je odabrala — ako se u istoj sesiji
 * pojavi drugi NIAS identitet, subjekt se ignorira ({@link #current}).
 *
 * <p>Log bilježi životni ciklus odabira (odabran, ponovo potvrđen, odbijen, poništen) bez OIB-ova:
 * korisnik može upisati i OIB fizičke osobe, a ishod poziva i šifru već logira klijent.
 */
@Service
public class ActingSubjectService {

    public static final String SESSION_KEY = ActingSubjectService.class.getName() + ".SUBJECT";

    private static final Logger log = LoggerFactory.getLogger(ActingSubjectService.class);

    private final EOvlastenjaClient eOvlastenjaClient;
    private final Clock clock;

    public ActingSubjectService(EOvlastenjaClient eOvlastenjaClient, Clock clock) {
        this.eOvlastenjaClient = eOvlastenjaClient;
        this.clock = clock;
    }

    /**
     * @throws BusinessException {@code error.actingSubject.invalidOib} za neispravan OIB tvrtke
     * @throws EOvlastenjaException kad e-Ovlaštenja ne potvrde zastupanje ili je sjednica nevažeća
     */
    public ActingSubject select(HttpSession session, NiasIdentity person, String legalOib) {
        String oib = legalOib == null ? null : legalOib.trim();
        if (!Oib.isValid(oib)) {
            throw new BusinessException("error.actingSubject.invalidOib");
        }
        if (oib.equals(person.oib())) {
            throw new BusinessException("error.actingSubject.self");
        }
        ActingSubject subject = verify(person, oib);
        session.setAttribute(SESSION_KEY, subject);
        log.info("acting_subject selected functions={}", subject.functions().size());
        return subject;
    }

    public void clear(HttpSession session) {
        if (session.getAttribute(SESSION_KEY) != null) {
            session.removeAttribute(SESSION_KEY);
            log.info("acting_subject cleared");
        }
    }

    /** Subjekt iz sesije, samo ako je odabran za istu NIAS osobu. */
    public Optional<ActingSubject> current(HttpSession session, String personOib) {
        if (session == null || personOib == null) {
            return Optional.empty();
        }
        return session.getAttribute(SESSION_KEY) instanceof ActingSubject s
                && personOib.equals(s.representativeOib())
                ? Optional.of(s) : Optional.empty();
    }

    /**
     * Ponovna provjera prije izdavanja RB-a: zastupanje ili NIAS sjednica mogli su prestati od
     * odabira. Neuspjeh briše subjekt iz sesije, da korisnik ne ostane „u ime" tvrtke koju više
     * ne zastupa.
     */
    public ActingSubject reverify(HttpSession session, NiasIdentity person, ActingSubject subject) {
        try {
            ActingSubject fresh = verify(person, subject.legalOib());
            session.setAttribute(SESSION_KEY, fresh);
            log.info("acting_subject reverified functions={}", fresh.functions().size());
            return fresh;
        } catch (EOvlastenjaException e) {
            session.removeAttribute(SESSION_KEY);
            log.info("acting_subject reverify_failed reason={} code={} — subjekt uklonjen iz sesije", e.reason(), e.code());
            throw e;
        }
    }

    private ActingSubject verify(NiasIdentity person, String legalOib) {
        Zastupanje z = eOvlastenjaClient.verifyRepresentation(person.sesijaId(), person.oib(), legalOib);
        return new ActingSubject(
                z.legalOib(),
                z.legalName(),
                z.functions().stream().map(Zastupanje.Funkcija::name).toList(),
                person.oib(),
                firstNonBlank(person.firstName(), z.personFirstName()),
                firstNonBlank(person.lastName(), z.personLastName()),
                Instant.now(clock));
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a.trim() : b;
    }
}
