package com.str.backend.auth.nias;

import com.str.backend.lessor.LessorRepository;
import com.str.backend.str.StrSubjectRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Kontakt kojim {@code GET /api/nias/subject} predpopunjava obrazac za RB.
 *
 * <p>Osoba u svoje ime: snapshot zadnjeg RB-a, s prednošću RB-a istog objekta
 * ({@link LessorRepository#findRbSnapshots}), a tek bez njega {@code str.document_contact} iz
 * eTurizma. Snapshot je ono što je korisnik sam zadnji upisao i potvrdio, pa se uzima cijeli — polje
 * koje je ondje ostavio prazno dokument ne dopunjava, da se ne miješaju dva izvora. Snapshot bez
 * mobitela (RB iz vremena prije obaveznog kontakta) se ne računa: obrazac tada ide kao da RB-a nema.
 *
 * <p>Tvrtka: samo {@code document_contact} tvrtke. Snapshot tvrtke nosi kontakt tadašnjeg zastupnika,
 * koji je danas možda više ne zastupa — isto pravilo kao predpopuna ponovnog zahtjeva.
 */
@Component
class SubjectContactResolver {

    private final LessorRepository lessorRepository;
    private final StrSubjectRepository strSubjectRepository;
    private final Clock clock;
    private final int retentionMonths;

    SubjectContactResolver(LessorRepository lessorRepository, StrSubjectRepository strSubjectRepository, Clock clock,
                           @Value("${app.rn.retention-months:18}") int retentionMonths) {
        this.lessorRepository = lessorRepository;
        this.strSubjectRepository = strSubjectRepository;
        this.clock = clock;
        this.retentionMonths = retentionMonths;
    }

    /**
     * @param oib        OIB osobe iz sesije
     * @param facilityId eTurizam objekt na obrascu, ili {@code null}
     * @param previousRn povučeni RB ponovnog zahtjeva, ili {@code null}
     */
    SubjectContact forPerson(String oib, String facilityId, String previousRn) {
        LocalDate retentionCutoff = LocalDate.now(clock).minusMonths(retentionMonths);
        return lessorRepository.findLatestRbSnapshot(oib, blankToNull(facilityId), blankToNull(previousRn),
                        retentionCutoff)
                .filter(l -> hasText(l.getMobileNumber()))
                .map(SubjectContact::ofRb)
                .orElseGet(() -> document(oib));
    }

    /** @param legalOib OIB tvrtke u čije ime osoba djeluje */
    SubjectContact forLegalEntity(String legalOib) {
        return document(legalOib);
    }

    private SubjectContact document(String oib) {
        return SubjectContact.ofDocument(strSubjectRepository.findDocumentContactByOib(oib).orElse(null));
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String blankToNull(String s) {
        return hasText(s) ? s.trim() : null;
    }
}
