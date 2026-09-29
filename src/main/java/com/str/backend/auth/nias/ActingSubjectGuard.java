package com.str.backend.auth.nias;

import com.str.backend.auth.LessorPrincipal;
import com.str.backend.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Štiti radnje od promjene subjekta između pripreme i izvršenja (TOCTOU). Odabrana tvrtka vrijedi za
 * cijelu sesiju, pa i za druge prozore: obrazac otvoren u svoje ime mogao bi se predati nakon što je
 * korisnik u drugom prozoru odabrao tvrtku, i backend bi radnju tiho izvršio za tvrtku.
 *
 * <p>Frontend zato šalje za koga je radnju pripremio — u tijelu (OIB kod izdavanja RB-a) ili u
 * zaglavlju {@value #HEADER} ({@code OIB tvrtke} ili {@value #SELF}). Kad se to ne slaže sa sesijom,
 * odgovor je 409 {@code ACTING_SUBJECT_CHANGED} i ništa se ne izvodi. Provjera ide prije ponovne
 * potvrde kroz e-Ovlaštenja, da se za odbijen zahtjev ne troši poziv FINA-i.
 */
@Component
public class ActingSubjectGuard {

    public static final String HEADER = "X-Acting-Subject";
    public static final String SELF = "SELF";

    private static final Logger log = LoggerFactory.getLogger(ActingSubjectGuard.class);
    private static final Pattern OIB = Pattern.compile("\\d{11}");

    private final NiasOibResolver niasOibResolver;
    private final EffectiveOibResolver effectiveOibResolver;

    public ActingSubjectGuard(NiasOibResolver niasOibResolver, EffectiveOibResolver effectiveOibResolver) {
        this.niasOibResolver = niasOibResolver;
        this.effectiveOibResolver = effectiveOibResolver;
    }

    /**
     * Provjera zaglavlja {@value #HEADER}. Bez zaglavlja nema provjere (kompatibilnost s frontendom
     * koji ga još ne šalje); isto za prijavu koja nije NIAS (non-EU iznajmljivač nema subjekta).
     *
     * @throws BusinessException             zaglavlje nije OIB ni {@value #SELF} (400)
     * @throws ActingSubjectChangedException zaglavlje se ne slaže sa sesijom (409)
     */
    public void requireUnchanged(Authentication authentication, String header) {
        if (header == null || header.isBlank()
                || (authentication != null && authentication.getPrincipal() instanceof LessorPrincipal)) {
            return;
        }
        Optional<String> person = niasOibResolver.resolve(authentication);
        if (person.isEmpty()) {
            return;
        }
        String value = header.trim();
        boolean self = SELF.equalsIgnoreCase(value);
        if (!self && !OIB.matcher(value).matches()) {
            throw new BusinessException("error.actingSubject.invalidHeader");
        }
        requireOwner(self ? person.get() : value, person.get(), currentSubject(authentication));
    }

    /**
     * Provjera vlasnika iz tijela zahtjeva: {@code expectedOwner} mora biti tvrtka iz sesije ili, kad
     * subjekta nema, sama osoba.
     *
     * @param current OIB tvrtke iz sesije, {@code null} kad se djeluje u svoje ime
     */
    public static void requireOwner(String expectedOwner, String personOib, String current) {
        String actual = current != null ? current : personOib;
        if (!actual.equals(expectedOwner)) {
            // Bez OIB-ova: samo vrsta vlasnika, da se u logu vidi smjer promjene.
            String expected = expectedOwner.equals(personOib) ? SELF : "tvrtka";
            String now = current != null ? (expected.equals(SELF) ? "tvrtka" : "druga tvrtka") : SELF;
            log.info("acting_subject_changed expected={} current={}", expected, now);
            throw new ActingSubjectChangedException(current);
        }
    }

    private String currentSubject(Authentication authentication) {
        return effectiveOibResolver.actingSubject(authentication).map(ActingSubject::legalOib).orElse(null);
    }
}
