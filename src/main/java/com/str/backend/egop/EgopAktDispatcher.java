package com.str.backend.egop;

import com.str.backend.common.Uuids;
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
    private final EgopAktiBezSifre bezSifre;

    public EgopAktDispatcher(EgopFilingStore store,
                             LessorRepository lessorRepository,
                             EgopFilingService filingService,
                             EgopRetryPolicy retryPolicy,
                             EgopAktKorisnik korisnik,
                             StrDocumentService documentService,
                             RnLifecycleLookup lookup,
                             EgopAktiBezSifre bezSifre) {
        this.store = store;
        this.lessorRepository = lessorRepository;
        this.filingService = filingService;
        this.retryPolicy = retryPolicy;
        this.korisnik = korisnik;
        this.documentService = documentService;
        this.lookup = lookup;
        this.bezSifre = bezSifre;
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

        if (!akt.isFiled() && cekaPodnesak(akt)) {
            return;
        }

        try {
            filingService.fileAct(akt, submission, lessor, korisnik.za(akt), filing -> konacniDokument(akt, filing));
        } catch (EgopException | RuntimeException e) {
            handleFailure(akt, e);
        }
    }

    /**
     * Akt kojem prethodi podnesak stranke ({@link StrDocumentType#podnesak()}, npr. obavijest o
     * opozivu iza zahtjeva za opoziv) urudžbira se tek kad je podnesak dobio pismeno u eGOP-u — inače
     * bi obavijest dobila manji urbroj od zahtjeva kojim je postupak pokrenut. Podnesak koji još nije
     * urudžbiran i smije se pokušati šalje se ovdje, prvi; ne uspije li, akt se <b>odgađa bez trošenja
     * pokušaja</b> ({@link EgopFilingStore#deferAkt}) do sljedećeg pokušaja podneska, pa ga retry job
     * pokuša opet, iza podneska (kandidati idu po vremenu nastanka). Čekanje nije kvar akta: ranije je
     * svako odgađanje trošilo pokušaj i logiralo ERROR sa stack traceom, pa bi akt bio iscrpljen prije
     * podneska.
     *
     * <p>Ne čeka se podnesak kojeg nema (opozivi zapisani prije T15, pad rendera podneska), podnesak
     * čija je vrsta na popisu {@code str.egop.akti-bez-sifre} — on se ne urudžbira, pa bi akt čekao
     * zauvijek — ni podnesak koji je iscrpio pokušaje: akt (i dostava obavijesti o opozivu, koja čeka
     * njegov pečat) tada ide bez njega, uz glasan ERROR.
     *
     * @return {@code true} ako akt mora pričekati
     */
    private boolean cekaPodnesak(EgopPismenoEntity akt) {
        Optional<EgopPismenoEntity> podnesak = StrDocumentType.fromVrstaPismenaNaziv(akt.getVrstaPismenaNaziv())
                .flatMap(StrDocumentType::podnesak)
                .filter(bezSifre::urudzbiv)
                .flatMap(vrsta -> store.findPismeno(akt.getSubmissionId(), vrsta.vrstaPismenaNaziv(), akt.getActRef()));
        if (podnesak.isEmpty() || podnesak.get().isFiled()) {
            return false;
        }
        EgopPismenoEntity p = podnesak.get();
        Instant now = Instant.now();
        if (!retryPolicy.isExhausted(p.getSyncAttempts())
                && (p.getNextAttemptAt() == null || !p.getNextAttemptAt().isAfter(now))) {
            dispatch(p.getId());
            p = store.findAkt(p.getId()).orElse(p);
        }
        if (p.isFiled()) {
            return false;
        }
        if (retryPolicy.isExhausted(p.getSyncAttempts())) {
            log.error("egop_akt_podnesak_exhausted akt={} rn={} vrsta='{}' podnesak={} podnesak_attempts={}"
                            + " last_error={} — podnesak se više ne pokušava; akt se urudžbira bez njega."
                            + " Podnesak urudžbiti ručno (urbroj će mu biti veći od akta)",
                    akt.getId(), akt.getRn(), akt.getVrstaPismenaNaziv(), p.getId(), p.getSyncAttempts(),
                    p.getSyncError());
            return false;
        }
        Instant nextAttemptAt = p.getNextAttemptAt() != null && p.getNextAttemptAt().isAfter(now)
                ? p.getNextAttemptAt()
                : retryPolicy.nextAttemptAt(now, 0);
        store.deferAkt(akt.getId(), "Čeka urudžbu podneska " + p.getId() + " ('" + p.getVrstaPismenaNaziv()
                + "') — akt ide u isti predmet iza njega", nextAttemptAt);
        log.info("egop_akt_deferred reason=podnesak_not_filed akt={} rn={} vrsta='{}' podnesak={}"
                        + " podnesak_attempts={} next_attempt_at={}",
                akt.getId(), akt.getRn(), akt.getVrstaPismenaNaziv(), p.getId(), p.getSyncAttempts(), nextAttemptAt);
        return true;
    }

    /**
     * Akt renderiran s klasom predmeta akata i urbrojem. Renderira se iz stanja RB-a, pa samo dok
     * je prijelaz akta još zadnji za RB: noviji prijelaz briše npr. rok suspenzije, i dokument bi
     * pod istim urbrojem rekao nešto drugo. Tada, i kad render padne, prilaže se snimak iz
     * trenutka događaja — urudžba ne smije stati zbog dokumenta koji već postoji.
     */
    private Optional<byte[]> konacniDokument(EgopPismenoEntity akt, FilingReference filing) {
        Optional<StrDocumentType> vrsta = StrDocumentType.fromVrstaPismenaNaziv(akt.getVrstaPismenaNaziv());
        Optional<RnLifecycleEvent> event = Uuids.parse(akt.getActRef()).flatMap(lookup::findEvent);
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
