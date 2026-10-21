package com.str.backend.egop;

import com.str.backend.document.FilingReference;
import com.str.backend.document.StrDocumentService;
import com.str.backend.document.StrDocumentType;
import com.str.backend.egop.exception.EgopException;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.rn.RnLifecycleLookup;
import com.str.backend.rn.event.RnLifecycleEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Urudžbira jedan akt životnog ciklusa RB-a (opoziv, suspenzija, povlačenje, reaktivacija) u
 * predmet akata tog RB-a (klasa 334-06/11), pod korisnikom koji je radnju napravio
 * ({@link EgopAktKorisnik}).
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
    private final EgopAktKorisnik korisnik;
    private final StrDocumentService documentService;
    private final RnLifecycleLookup lookup;

    public EgopAktDispatcher(EgopFilingStore store,
                             LessorRepository lessorRepository,
                             EgopFilingService filingService,
                             EgopRetryPolicy retryPolicy,
                             EgopAktKorisnik korisnik,
                             StrDocumentService documentService,
                             RnLifecycleLookup lookup) {
        this.store = store;
        this.lessorRepository = lessorRepository;
        this.filingService = filingService;
        this.retryPolicy = retryPolicy;
        this.korisnik = korisnik;
        this.documentService = documentService;
        this.lookup = lookup;
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
            filingService.fileAct(akt, submission, lessor, korisnik.za(akt), filing -> konacniDokument(akt, filing));
        } catch (EgopException | RuntimeException e) {
            handleFailure(akt, e);
        }
    }

    /**
     * Akt renderiran s klasom predmeta akata i urbrojem. Renderira se iz stanja RB-a, pa samo dok
     * je prijelaz akta još zadnji za RB: noviji prijelaz briše npr. rok suspenzije, i dokument bi
     * pod istim urbrojem rekao nešto drugo. Tada, i kad render padne, prilaže se snimak iz
     * trenutka događaja — urudžba ne smije stati zbog dokumenta koji već postoji.
     */
    private Optional<byte[]> konacniDokument(EgopPismenoEntity akt, FilingReference filing) {
        Optional<StrDocumentType> vrsta = StrDocumentType.fromVrstaPismenaNaziv(akt.getVrstaPismenaNaziv());
        Optional<RnLifecycleEvent> event = logId(akt).flatMap(lookup::findEvent);
        if (vrsta.isEmpty() || event.isEmpty()) {
            log.warn("egop_akt_snapshot_kept reason=missing_event akt={} rn={}", akt.getId(), akt.getRn());
            return Optional.empty();
        }
        if (!lookup.isLatestTransition(akt.getRn(), event.get().logId())) {
            log.warn("egop_akt_snapshot_kept reason=superseded akt={} rn={} vrsta='{}' — RB je u međuvremenu"
                            + " promijenio status; prilaže se dokument iz trenutka događaja, bez klase i urbroja",
                    akt.getId(), akt.getRn(), akt.getVrstaPismenaNaziv());
            return Optional.empty();
        }
        try {
            return Optional.of(documentService.render(vrsta.get(), akt.getRn(), event.get().reason(), filing));
        } catch (RuntimeException e) {
            log.error("egop_akt_render_failed akt={} rn={} vrsta='{}': {} — prilaže se dokument iz trenutka"
                    + " događaja", akt.getId(), akt.getRn(), akt.getVrstaPismenaNaziv(), e.getMessage(), e);
            return Optional.empty();
        }
    }

    private static Optional<UUID> logId(EgopPismenoEntity akt) {
        try {
            return Optional.of(UUID.fromString(akt.getActRef()));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
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
