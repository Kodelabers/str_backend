package com.str.backend.auth.nias;

import com.str.backend.common.Oib;
import com.str.backend.exception.BusinessException;
import com.str.backend.exception.ExternalRegistryException;
import com.str.backend.registries.eovlastenja.EOvlastenjaClient;
import com.str.backend.registries.eovlastenja.EOvlastenjaException;
import com.str.backend.registries.eovlastenja.ZastupanaTvrtka;
import com.str.backend.registries.eovlastenja.Zastupanje;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.Serializable;
import java.text.Collator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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
    /** Kratko čuvan popis tvrtki (GetNavigationData), da se FINA ne zove pri svakom otvaranju izbornika. */
    public static final String OPTIONS_KEY = ActingSubjectService.class.getName() + ".OPTIONS";
    private static final Duration OPTIONS_TTL = Duration.ofMinutes(5);
    /** Neuspjeh se pamti kraće — da ispad FINA-e ne znači novi poziv (i timeout) pri svakom otvaranju izbornika. */
    private static final Duration OPTIONS_FAILURE_TTL = Duration.ofSeconds(60);

    private static final Logger log = LoggerFactory.getLogger(ActingSubjectService.class);

    private final EOvlastenjaClient eOvlastenjaClient;
    private final ActingSubjectRateLimiter rateLimiter;
    private final Clock clock;

    public ActingSubjectService(EOvlastenjaClient eOvlastenjaClient, ActingSubjectRateLimiter rateLimiter,
                                Clock clock) {
        this.eOvlastenjaClient = eOvlastenjaClient;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    /**
     * Neispravan i vlastiti OIB odbijaju se lokalno, prije ograničenja broja odabira — ne troše kvotu.
     *
     * @throws BusinessException {@code error.actingSubject.invalidOib} za neispravan OIB tvrtke
     * @throws ActingSubjectRateLimitException previše odabira u kratkom vremenu (429)
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
        rateLimiter.acquire(person.oib(), session.getId());
        ActingSubject subject = verify(person, oib);
        session.setAttribute(SESSION_KEY, subject);
        log.info("acting_subject selected functions={}", subject.functions().size());
        return subject;
    }

    /**
     * Tvrtke koje osoba zastupa po zakonu, za izbornik „Djelujem u ime" — sortirane po nazivu.
     * Popis je nepotpisan (v. {@link EOvlastenjaClient#representedCompanies}) i ništa ne odobrava:
     * odabir i dalje ide kroz {@link #select} s potpisanom provjerom. Vrijedi samo za osobu za koju
     * je dohvaćen; u sesiji se čuva 5 minuta, a neuspjeh 60 sekundi.
     *
     * <ul>
     *   <li>FINA javlja da osoba nije u e-Ovlaštenjima / nema privolu / subjekt ne postoji → prazan
     *       popis (samo uskraćuje, pa je siguran i iz nepotpisanog odgovora);</li>
     *   <li>nevažeća sjednica, nedostupnost, šifra 100, isključeno na okolini →
     *       {@link ActingSubjectOptionsUnavailableException} (503). Ne 401: popis frontend dohvaća
     *       sam, pa bi 401 pokrenuo ponovnu prijavu koju korisnik nije tražio.</li>
     * </ul>
     */
    public List<ZastupanaTvrtka> representedCompanies(HttpSession session, NiasIdentity person) {
        Instant now = Instant.now(clock);
        if (session.getAttribute(OPTIONS_KEY) instanceof CachedOptions cached
                && person.oib().equals(cached.personOib())) {
            if (cached.unavailable() && now.isBefore(cached.fetchedAt().plus(OPTIONS_FAILURE_TTL))) {
                throw new ActingSubjectOptionsUnavailableException();
            }
            if (!cached.unavailable() && now.isBefore(cached.fetchedAt().plus(OPTIONS_TTL))) {
                return cached.companies();
            }
        }
        List<ZastupanaTvrtka> fetched;
        try {
            fetched = eOvlastenjaClient.representedCompanies(person.sesijaId(), person.oib());
        } catch (EOvlastenjaException e) {
            if (e.reason() == EOvlastenjaException.Reason.SESSION) {
                throw unavailable(session, person, now, "reason=SESSION code=" + e.code());
            }
            fetched = List.of();   // nije u e-Ovlaštenjima / nema privolu / nema subjekta
        } catch (ExternalRegistryException e) {
            throw unavailable(session, person, now, e.getMessage());
        }
        Collator hr = Collator.getInstance(Locale.forLanguageTag("hr"));
        List<ZastupanaTvrtka> companies = fetched.stream()
                .filter(c -> !c.oib().equals(person.oib()))
                .sorted(Comparator.comparing((ZastupanaTvrtka c) -> c.naziv() != null ? c.naziv() : c.oib(), hr))
                .toList();
        session.setAttribute(OPTIONS_KEY, new CachedOptions(person.oib(), now, companies, false));
        log.info("acting_subject options companies={}", companies.size());
        return companies;
    }

    private static ActingSubjectOptionsUnavailableException unavailable(HttpSession session, NiasIdentity person,
                                                                         Instant now, String cause) {
        session.setAttribute(OPTIONS_KEY, new CachedOptions(person.oib(), now, List.of(), true));
        // Jedan WARN po minuti i sesiji, bez stack tracea; uzrok je naša poruka ili šifra, bez osobnih podataka.
        log.warn("acting_subject options_unavailable cause={}", cause);
        return new ActingSubjectOptionsUnavailableException();
    }

    /** Popis (ili neuspjeh) iz sesije; {@link Serializable} zbog Spring Session JDBC. */
    record CachedOptions(String personOib, Instant fetchedAt, List<ZastupanaTvrtka> companies,
                         boolean unavailable) implements Serializable {
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
