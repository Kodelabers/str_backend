package com.str.backend.egop;

import com.str.backend.common.Uuids;
import com.str.backend.common.VrstaDatoteke;
import com.str.backend.document.FilingReference;
import com.str.backend.document.StrDocumentService;
import com.str.backend.document.StrDocumentType;
import com.str.backend.egop.exception.EgopException;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.objection.SuspensionObjectionEntity;
import com.str.backend.objection.SuspensionObjectionLookup;
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
 * <p>Isti dispatcher šalje i podneske stranke na akt (očitovanje, prigovor; {@code act_ref}
 * {@code PRIGOVOR:<objection_id>}): ulazno pismeno s dokumentom koji je stranka učitala, iza akta na koji
 * odgovara ({@link #dispatchPodnesak}).
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
    private final SuspensionObjectionLookup prigovori;

    public EgopAktDispatcher(EgopFilingStore store,
                             LessorRepository lessorRepository,
                             EgopFilingService filingService,
                             EgopRetryPolicy retryPolicy,
                             EgopAktKorisnik korisnik,
                             StrDocumentService documentService,
                             RnLifecycleLookup lookup,
                             EgopAktiBezSifre bezSifre,
                             SuspensionObjectionLookup prigovori) {
        this.store = store;
        this.lessorRepository = lessorRepository;
        this.filingService = filingService;
        this.retryPolicy = retryPolicy;
        this.korisnik = korisnik;
        this.documentService = documentService;
        this.lookup = lookup;
        this.bezSifre = bezSifre;
        this.prigovori = prigovori;
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

        Optional<UUID> objectionId = akt.objectionId();
        if (objectionId.isPresent()) {
            dispatchPodnesak(akt, objectionId.get(), submission, lessor);
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
     * bi obavijest dobila manji urbroj od zahtjeva kojim je postupak pokrenut ({@link #cekaPrethodno}).
     * Iscrpljen podnesak ne zadržava akt (ni dostavu obavijesti o opozivu, koja čeka njegov pečat).
     *
     * @return {@code true} ako akt mora pričekati
     */
    private boolean cekaPodnesak(EgopPismenoEntity akt) {
        Optional<EgopPismenoEntity> podnesak = StrDocumentType.fromVrstaPismenaNaziv(akt.getVrstaPismenaNaziv())
                .flatMap(StrDocumentType::podnesak)
                .filter(bezSifre::urudzbiv)
                .flatMap(vrsta -> store.findPismeno(akt.getSubmissionId(), vrsta.vrstaPismenaNaziv(), akt.getActRef()));
        return cekaPrethodno(akt, podnesak, "podnesak", "podneska");
    }

    /**
     * Podnesak stranke na akt (očitovanje, prigovor) ide u eGOP kao ulazno pismeno, s dokumentom koji je
     * stranka učitala ({@code suspension_objection.file_content}) i njezinim ostalim datotekama kao prilozima.
     * U predmet ide tek <b>iza</b> akta na koji odgovara ({@link StrDocumentType#pobijaniAkt()}, prijelaz
     * {@code rn_log_id}): dok taj akt nije urudžbiran, podnesak čeka bez trošenja pokušaja, kao obavijest o
     * opozivu iza zahtjeva ({@link #cekaPrethodno}). Pad slanja troši pokušaj i ponavlja ga {@link EgopRetryJob}.
     */
    private void dispatchPodnesak(EgopPismenoEntity podnesak, UUID objectionId, SubmissionEntity submission,
                                  LessorEntity lessor) {
        try {
            SuspensionObjectionEntity prigovor = prigovori.find(objectionId)
                    .orElseThrow(() -> new IllegalStateException("Prigovor " + objectionId
                            + " ne postoji — podnesak nema dokument"));
            if (!podnesak.isFiled() && cekaAkt(podnesak, prigovor.getRnLogId())) {
                return;
            }
            filingService.filePodnesak(podnesak, submission, lessor, korisnik.za(podnesak), dokument(prigovor));
        } catch (EgopException | RuntimeException e) {
            handleFailure(podnesak, e);
        }
    }

    /**
     * Akt na koji podnesak odgovara: pismeno prijelaza {@code rnLogId} vrste {@link StrDocumentType#pobijaniAkt()}.
     * Akt koji uopće nije zapisan ({@code egop_akt_not_recorded}, npr. pad rendera) ne zadržava podnesak, ali se
     * javlja zasebnim ERROR-om — drukčiji kvar od iscrpljenog akta ({@code egop_akt_pobijani_akt_exhausted}).
     */
    private boolean cekaAkt(EgopPismenoEntity podnesak, UUID rnLogId) {
        Optional<StrDocumentType> vrstaAkta = rnLogId == null ? Optional.empty()
                : StrDocumentType.fromVrstaPismenaNaziv(podnesak.getVrstaPismenaNaziv())
                        .flatMap(StrDocumentType::pobijaniAkt)
                        .filter(bezSifre::urudzbiv);
        if (vrstaAkta.isEmpty()) {
            return false;
        }
        Optional<EgopPismenoEntity> akt = store.findPismeno(podnesak.getSubmissionId(),
                vrstaAkta.get().vrstaPismenaNaziv(), rnLogId.toString());
        if (akt.isEmpty()) {
            log.error("egop_podnesak_bez_pobijanog_akta podnesak={} rn={} vrsta='{}' rn_log={} pobijani_akt='{}' — akt"
                            + " na koji podnesak odgovara nije zapisan u egop_pismeno (egop_akt_not_recorded); podnesak"
                            + " se urudžbira bez njega, akt urudžbiti ručno (urbroj će mu biti veći)",
                    podnesak.getId(), podnesak.getRn(), podnesak.getVrstaPismenaNaziv(), rnLogId,
                    vrstaAkta.get().vrstaPismenaNaziv());
            return false;
        }
        // Ručni akt županije nema eGOP pismeno, a podnesak na njega se ni ne zapisuje
        // (PodnesakFilingListener) — ovo samo brani od vječnog čekanja.
        return cekaPrethodno(podnesak, akt.filter(a -> !a.isRucno()), "pobijani_akt", "akta");
    }

    /** Glavni dokument kakav je učitan; ekstenzija iz vrste koju je sadržaj potvrdio pri predaji. */
    private static EgopFilingService.PodnesakDokument dokument(SuspensionObjectionEntity prigovor) {
        String ekstenzija = VrstaDatoteke.poContentType(prigovor.getContentType())
                .map(VrstaDatoteke::ekstenzija)
                .orElseThrow(() -> new IllegalStateException("Prigovor " + prigovor.getObjectionId()
                        + " ima nepoznat content_type '" + prigovor.getContentType() + "'"));
        return new EgopFilingService.PodnesakDokument(prigovor.getFileContent(), ekstenzija);
    }

    /**
     * Pismeno koje mora ići iza drugog pismena istog postupka ({@code prethodno}) urudžbira se tek kad je
     * {@code prethodno} dobilo pismeno u eGOP-u — inače bi dobilo manji urbroj od njega. Prethodno koje još
     * nije urudžbirano i smije se pokušati šalje se ovdje, prvo; ne uspije li, pismeno se <b>odgađa bez
     * trošenja pokušaja</b> ({@link EgopFilingStore#deferAkt}) do sljedećeg pokušaja prethodnog, pa ga retry
     * job pokuša opet, iza njega (kandidati idu po vremenu nastanka). Čekanje nije kvar pismena: ranije je
     * svako odgađanje trošilo pokušaj i logiralo ERROR sa stack traceom, pa bi pismeno bilo iscrpljeno prije
     * prethodnog.
     *
     * <p>Ne čeka se prethodno kojeg nema (npr. opozivi zapisani prije T15, pad rendera), prethodno čija je
     * vrsta na popisu {@code str.egop.akti-bez-sifre} — ono se ne urudžbira, pa bi pismeno čekalo zauvijek
     * (pozivatelj ga tada ne predaje) — ni prethodno koje je iscrpilo pokušaje: pismeno tada ide bez njega,
     * uz glasan ERROR.
     *
     * @param uloga   oznaka prethodnog pismena u logu ({@code podnesak}, {@code pobijani_akt})
     * @param genitiv oznaka prethodnog pismena u razlogu odgode ({@code sync_error})
     * @return {@code true} ako pismeno mora pričekati
     */
    private boolean cekaPrethodno(EgopPismenoEntity akt, Optional<EgopPismenoEntity> prethodno, String uloga,
                                  String genitiv) {
        if (prethodno.isEmpty() || prethodno.get().isFiled()) {
            return false;
        }
        EgopPismenoEntity p = prethodno.get();
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
            log.error("egop_akt_{}_exhausted akt={} rn={} vrsta='{}' prethodno={} prethodno_vrsta='{}'"
                            + " prethodno_attempts={} last_error={} — prethodno pismeno se više ne pokušava; ovo se"
                            + " urudžbira bez njega. Prethodno urudžbiti ručno (urbroj će mu biti veći)",
                    uloga, akt.getId(), akt.getRn(), akt.getVrstaPismenaNaziv(), p.getId(), p.getVrstaPismenaNaziv(),
                    p.getSyncAttempts(), p.getSyncError());
            return false;
        }
        Instant nextAttemptAt = p.getNextAttemptAt() != null && p.getNextAttemptAt().isAfter(now)
                ? p.getNextAttemptAt()
                : retryPolicy.nextAttemptAt(now, 0);
        store.deferAkt(akt.getId(), "Čeka urudžbu " + genitiv + " " + p.getId() + " ('" + p.getVrstaPismenaNaziv()
                + "') — ide u isti predmet iza njega", nextAttemptAt);
        log.info("egop_akt_deferred reason={}_not_filed akt={} rn={} vrsta='{}' prethodno={}"
                        + " prethodno_attempts={} next_attempt_at={}",
                uloga, akt.getId(), akt.getRn(), akt.getVrstaPismenaNaziv(), p.getId(), p.getSyncAttempts(),
                nextAttemptAt);
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
