package com.str.backend.egop;

import com.str.backend.domain.EgopSyncStatus;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.request.SubmissionRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Sva perzistencija eGOP toka, svaki upis u <b>vlastitoj kratkoj transakciji</b>.
 *
 * <p>Postoji zato da SOAP pozivi ostanu <b>izvan</b> transakcije: jedno urudžbiranje
 * radi do ~7 poziva, pa bi jedna obuhvatna transakcija držala konekciju i (ranije)
 * pesimistički lock na iznajmljivaču minutama. Poziv se sad izmjenjuje ovako:
 * SOAP → kratki upis → SOAP → kratki upis.
 *
 * <p>Entiteti su između poziva odspojeni; {@code save(...)} radi merge. To je sigurno
 * jer nijedan od ovih entiteta nema {@code @Version} niti se oslanja na dirty-checking.
 */
@Component
public class EgopFilingStore {

    private final SubmissionRepository submissionRepository;
    private final LessorRepository lessorRepository;
    private final EgopPismenoRepository egopPismenoRepository;

    public EgopFilingStore(SubmissionRepository submissionRepository,
                           LessorRepository lessorRepository,
                           EgopPismenoRepository egopPismenoRepository) {
        this.submissionRepository = submissionRepository;
        this.lessorRepository = lessorRepository;
        this.egopPismenoRepository = egopPismenoRepository;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<Integer> readSubjektOznaka(UUID lessorId) {
        return lessorRepository.findById(lessorId).map(LessorEntity::getEgopSubjektOznaka);
    }

    /**
     * Upisuje oznaku subjekta samo ako je još prazna i vraća onu koja je na kraju na
     * snazi. Zamjenjuje raniji {@code SELECT ... FOR UPDATE}: uvjetni UPDATE ne drži
     * lock preko SOAP poziva, a utrku rješava jednako dobro — ako je druga registracija
     * bila brža, koristimo njezinu oznaku. Dvostruki {@code KreirajSubjekta} za isti OIB
     * nije opasan jer eGOP na lookup vraća postojećeg subjekta.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int storeSubjektOznaka(UUID lessorId, int oznaka) {
        int updated = lessorRepository.assignEgopSubjektIfAbsent(lessorId, oznaka);
        if (updated > 0) {
            return oznaka;
        }
        return lessorRepository.findById(lessorId)
                .map(LessorEntity::getEgopSubjektOznaka)
                .orElse(oznaka);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveSyncStatus(UUID submissionId, EgopSyncStatus status) {
        submissionRepository.findById(submissionId).ifPresent(submission -> {
            submission.applyEgopSyncStatus(status);
            submissionRepository.save(submission);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void savePredmet(UUID submissionId, int uredskaGodina, int rbrPredmeta, String klasa) {
        submissionRepository.findById(submissionId).ifPresent(submission -> {
            submission.applyEgopPredmet(uredskaGodina, rbrPredmeta, klasa);
            submissionRepository.save(submission);
        });
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<EgopPismenoEntity> findPismeno(UUID submissionId, String vrstaNaziv,
                                                   String actRef) {
        return egopPismenoRepository.findBySubmissionIdAndVrstaPismenaNazivAndActRef(
                submissionId, vrstaNaziv, actRef);
    }

    /**
     * Sprema pismeno; ako je paralelni tok (druga instanca, ili retry u utrci s inline
     * dispatchem) već upisao isto, unique constraint puca i vraćamo njegov redak umjesto
     * da inzistiramo na svom.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EgopPismenoEntity savePismeno(EgopPismenoEntity pismeno) {
        try {
            return egopPismenoRepository.saveAndFlush(pismeno);
        } catch (DataIntegrityViolationException e) {
            return egopPismenoRepository
                    .findBySubmissionIdAndVrstaPismenaNazivAndActRef(pismeno.getSubmissionId(),
                            pismeno.getVrstaPismenaNaziv(), pismeno.getActRef())
                    .orElseThrow(() -> e);
        }
    }

    // --- akti životnog ciklusa RB-a -------------------------------------------------

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<EgopPismenoEntity> findAkt(UUID aktId) {
        return egopPismenoRepository.findById(aktId);
    }

    /**
     * Zapisuje akt prije slanja u eGOP. Ako je isti akt već zapisan (retry listenera,
     * paralelna instanca), vraća postojeći — {@code act_ref} čini ključ jedinstvenim po
     * pojedinom prijelazu, pa ponovljena suspenzija ovdje ne kolidira sa starom.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EgopPismenoEntity saveAkt(EgopPismenoEntity akt) {
        return savePismeno(akt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void applyPismeno(UUID aktId, int jop, String urBroj) {
        egopPismenoRepository.findById(aktId).ifPresent(akt -> {
            akt.applyPismeno(jop, urBroj);
            egopPismenoRepository.save(akt);
        });
    }

    /** @return broj pokušaja nakon ovog neuspjeha */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int markAktFailed(UUID aktId, String error, Instant nextAttemptAt) {
        return egopPismenoRepository.findById(aktId).map(akt -> {
            akt.markFailed(error, nextAttemptAt);
            egopPismenoRepository.save(akt);
            return akt.getSyncAttempts();
        }).orElse(0);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDocumentAttached(UUID pismenoId) {
        egopPismenoRepository.findById(pismenoId).ifPresent(pismeno -> {
            pismeno.markDocumentAttached();
            egopPismenoRepository.save(pismeno);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveSuccess(UUID submissionId, String filingNumber, byte[] pdf) {
        submissionRepository.findById(submissionId).ifPresent(submission -> {
            submission.setFilingNumber(filingNumber);
            submission.setDocumentLink("egop://" + filingNumber);
            submission.setFilingDate(Instant.now());
            submission.setPdfContent(pdf);
            submission.applyEgopSyncStatus(EgopSyncStatus.SYNCED);
            submissionRepository.save(submission);
        });
    }

    /**
     * @param nextAttemptAt kad retry job smije pokušati ponovo (eksponencijalni backoff)
     * @param pdf           PDF bez urudžbenog broja, da korisnik ipak ima što preuzeti;
     *                      {@code null} ako ga nije bilo moguće renderirati
     * @return broj dosadašnjih pokušaja nakon ovog neuspjeha
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int markFailed(UUID submissionId, String error, Instant nextAttemptAt, byte[] pdf) {
        return submissionRepository.findById(submissionId).map(submission -> {
            submission.markEgopFailed(error, nextAttemptAt);
            if (pdf != null) {
                submission.setPdfContent(pdf);
            }
            submissionRepository.save(submission);
            return submission.getEgopSyncAttempts();
        }).orElse(0);
    }

    /** Oznaka poslane e-mail dostave — sprječava duplikate kroz sve retry pokušaje. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRnEmailSent(UUID submissionId) {
        submissionRepository.findById(submissionId).ifPresent(submission -> {
            submission.markRnEmailSent();
            submissionRepository.save(submission);
        });
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<SubmissionEntity> findSubmission(UUID submissionId) {
        return submissionRepository.findById(submissionId);
    }
}
