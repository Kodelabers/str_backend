package com.str.backend.egop;

import com.str.backend.egop.exception.EgopException;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.request.SubmissionEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Urudžbira jedan akt životnog ciklusa RB-a (opoziv, suspenzija, povlačenje, reaktivacija) u
 * isti neupravni predmet u kojem je registracija.
 *
 * <p>Odvojen od {@link EgopRegistrationDispatcher} jer su to različite jedinice posla:
 * registracija renderira PDF <i>iz</i> urudžbenog broja koji tek nastaje, pa mora ići u jednom
 * lancu; akt životnog ciklusa dolazi s već snimljenim dokumentom i sam je sebi jedinica
 * ponovnog pokušaja. Miješanje bi značilo da pad suspenzije mijenja status registracije.
 *
 * <p><b>Ne drži transakciju</b> preko SOAP poziva — upisi idu kroz {@link EgopFilingStore},
 * svaki u vlastitoj kratkoj transakciji.
 */
@Component
public class EgopAktDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EgopAktDispatcher.class);

    private final EgopFilingStore store;
    private final LessorRepository lessorRepository;
    private final EgopFilingService filingService;
    private final EgopRetryPolicy retryPolicy;

    public EgopAktDispatcher(EgopFilingStore store,
                             LessorRepository lessorRepository,
                             EgopFilingService filingService,
                             EgopRetryPolicy retryPolicy) {
        this.store = store;
        this.lessorRepository = lessorRepository;
        this.filingService = filingService;
        this.retryPolicy = retryPolicy;
    }

    /** Idempotentan: već urudžbiran akt se preskače, djelomično obavljen nastavlja gdje je stao. */
    public void dispatch(UUID aktId) {
        EgopPismenoEntity akt = store.findAkt(aktId).orElse(null);
        if (akt == null) {
            log.error("egop_akt_skipped reason=missing_akt akt={}", aktId);
            return;
        }
        if (akt.isSynced()) {
            log.debug("egop_akt_skipped reason=already_synced akt={} rn={}", aktId, akt.getRn());
            return;
        }

        SubmissionEntity submission = store.findSubmission(akt.getSubmissionId()).orElse(null);
        LessorEntity lessor = submission == null ? null
                : lessorRepository.findById(submission.getLessorId()).orElse(null);
        if (submission == null || lessor == null) {
            log.error("egop_akt_skipped reason=missing_entity akt={} submission={} lessor={}",
                    aktId, submission != null, lessor != null);
            return;
        }

        try {
            filingService.fileAct(akt, submission, lessor);
        } catch (EgopException | RuntimeException e) {
            handleFailure(akt, e);
        }
    }

    /**
     * Neuspjeh se bilježi na samom aktu — RB je valjan bez obzira na urudžbu, a obavijest
     * iznajmljivaču ide zasebnim putem (e-pošta), pa pad urudžbe ne smije zaustaviti ništa
     * drugo.
     */
    private void handleFailure(EgopPismenoEntity akt, Exception cause) {
        Instant nextAttemptAt = retryPolicy.nextAttemptAt(Instant.now(), akt.getSyncAttempts());
        int attempts = store.markAktFailed(akt.getId(), cause.getMessage(), nextAttemptAt);

        if (retryPolicy.isExhausted(attempts)) {
            log.error("egop_akt_retry_exhausted akt={} rn={} vrsta='{}' attempts={} last_error={}"
                            + " — akt se više neće pokušavati urudžbirati automatski",
                    akt.getId(), akt.getRn(), akt.getVrstaPismenaNaziv(), attempts,
                    cause.getMessage());
        } else {
            log.error("egop_akt_failed akt={} rn={} vrsta='{}' attempt={} next_attempt_at={}: {}",
                    akt.getId(), akt.getRn(), akt.getVrstaPismenaNaziv(), attempts, nextAttemptAt,
                    cause.getMessage(), cause);
        }
    }
}
