package com.str.backend.auth.nias;

import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

/**
 * Jedino mjesto koje na NIAS putu odgovara „za čiji OIB radimo": OIB pravne osobe u čije ime
 * korisnik djeluje (potvrđene kroz e-Ovlaštenja, {@link ActingSubjectService}), a inače OIB
 * same NIAS osobe (na local/mock konfigurirani mock OIB, {@link NiasOibResolver}).
 *
 * <p>Sve što je vezano uz vlasnika — popis RB-ova, objekti iz eTurizma, PDF, nacrti, izdavanje
 * RB-a — mora ići ovuda. Kad bi pojedino mjesto uzimalo OIB osobe izravno, zastupnik bi u ime
 * tvrtke vidio svoje, a ne tvrtkine RB-ove.
 */
@Component
public class EffectiveOibResolver {

    private final NiasOibResolver niasOibResolver;
    private final ActingSubjectService actingSubjectService;

    public EffectiveOibResolver(NiasOibResolver niasOibResolver, ActingSubjectService actingSubjectService) {
        this.niasOibResolver = niasOibResolver;
        this.actingSubjectService = actingSubjectService;
    }

    /** OIB za koji se radi; prazno kad nema NIAS identiteta (ni mocka). */
    public Optional<String> resolve(Authentication authentication) {
        Optional<String> personOib = niasOibResolver.resolve(authentication);
        if (personOib.isEmpty()) {
            return Optional.empty();
        }
        return actingSubject(personOib.get())
                .map(ActingSubject::legalOib)
                .or(() -> personOib);
    }

    /** Pravna osoba u čije ime se djeluje, ako je odabrana (i to za istu NIAS osobu). */
    public Optional<ActingSubject> actingSubject(Authentication authentication) {
        return niasOibResolver.resolve(authentication).flatMap(this::actingSubject);
    }

    /**
     * Kao {@link #actingSubject}, ali zastupanje prije toga <b>ponovo</b> potvrđuje kroz
     * e-Ovlaštenja — za radnje u ime tvrtke koje mijenjaju stanje (izdavanje i povlačenje RB-a,
     * upload rješenja). Odabir u sesiji može biti star, a zastupanje je moglo prestati.
     *
     * <p>Neuspjeh briše subjekt iz sesije i propagira se ({@code EOvlastenjaException} → 401/403/400,
     * nedostupnost → 503); radnja se tada ne izvodi. Prazno kad korisnik djeluje u svoje ime.
     */
    public Optional<ActingSubject> reverifiedActingSubject(Authentication authentication) {
        return actingSubject(authentication).map(subject -> {
            // Bez assertiona (local/mock) identitet je zastupnik iz odabira, s imenom iz njega.
            NiasIdentity person = NiasOibExtractor.extractIdentity(authentication)
                    .orElseGet(() -> new NiasIdentity(subject.representativeOib(),
                            subject.representativeFirstName(), subject.representativeLastName()));
            // Subjekt je iz sesije, pa ona postoji; getSession(false) je ne stvara usput.
            return actingSubjectService.reverify(currentSession(), person, subject);
        });
    }

    private Optional<ActingSubject> actingSubject(String personOib) {
        return actingSubjectService.current(currentSession(), personOib);
    }

    private static HttpSession currentSession() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        return attrs instanceof ServletRequestAttributes s ? s.getRequest().getSession(false) : null;
    }
}
