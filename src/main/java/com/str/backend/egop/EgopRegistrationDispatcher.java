package com.str.backend.egop;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.document.FilingReference;
import com.str.backend.document.StrDocumentService;
import com.str.backend.document.StrDocumentType;
import com.str.backend.domain.RnStatus;
import com.str.backend.email.EmailService;
import com.str.backend.email.MailRetryEntity;
import com.str.backend.email.MailRetryStore;
import com.str.backend.email.RnIssuedMail;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.lookup.AccommodationTypeRepository;
import com.str.backend.pdf.SubmissionPdfContext;
import com.str.backend.pdf.SubmissionPdfGenerator;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnRepository;
import com.str.backend.request.SubmissionEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Urudžbira jednu registraciju u eGOP i šalje iznajmljivaču obavijest o izdanom RB-u
 * e-mailom (non-EU s PDF-om u privitku jer mu je to dostava; ostalima bez privitka).
 * Sve ulazne podatke rekonstruira iz baze po {@code submissionId}, pa je isti
 * ulaz siguran i za prvi pokušaj (nakon izdavanja RN-a) i za naknadni retry —
 * dijele ga {@link com.str.backend.registration.event.RnIssuedListener} i
 * {@link EgopRetryJob}.
 *
 * <p><b>Ne drži transakciju</b> preko SOAP poziva: upisi idu kroz
 * {@link EgopFilingStore}, svaki u vlastitoj kratkoj transakciji. Greška se hvata i
 * bilježi ({@code egop_sync_status=FAILED} + broj pokušaja + vrijeme sljedećeg
 * pokušaja), RN ostaje valjan.
 *
 * <p>Obavijest e-mailom je idempotentna preko {@code submission.rn_email_sent_at} —
 * bez toga bi svaki retry iznajmljivaču poslao još jedan identičan mail. Oznaka se upisuje
 * samo kad je poruka stvarno predana SMTP-u, da neuspjelo slanje retry ne preskoči.
 *
 * <p>Neuspjelo slanje ide u {@link MailRetryStore}; ponavlja ga {@code MailRetryJob} preko
 * {@link #retryEmail}, neovisno o tome je li urudžbiranje prošlo.
 */
@Component
public class EgopRegistrationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EgopRegistrationDispatcher.class);

    private final EgopFilingStore store;
    private final AccommodationRepository accommodationRepository;
    private final LessorRepository lessorRepository;
    private final RnRepository rnRepository;
    private final AccommodationTypeRepository accommodationTypeRepository;
    private final SubmissionPdfGenerator pdfGenerator;
    private final StrDocumentService documentService;
    private final EgopFilingService egopFilingService;
    private final EgopRetryPolicy retryPolicy;
    private final EmailService emailService;
    private final MailRetryStore mailRetryStore;

    public EgopRegistrationDispatcher(EgopFilingStore store,
                                      AccommodationRepository accommodationRepository,
                                      LessorRepository lessorRepository,
                                      RnRepository rnRepository,
                                      AccommodationTypeRepository accommodationTypeRepository,
                                      SubmissionPdfGenerator pdfGenerator,
                                      StrDocumentService documentService,
                                      EgopFilingService egopFilingService,
                                      EgopRetryPolicy retryPolicy,
                                      EmailService emailService,
                                      MailRetryStore mailRetryStore) {
        this.store = store;
        this.accommodationRepository = accommodationRepository;
        this.lessorRepository = lessorRepository;
        this.rnRepository = rnRepository;
        this.accommodationTypeRepository = accommodationTypeRepository;
        this.pdfGenerator = pdfGenerator;
        this.documentService = documentService;
        this.egopFilingService = egopFilingService;
        this.retryPolicy = retryPolicy;
        this.emailService = emailService;
        this.mailRetryStore = mailRetryStore;
    }

    /** Sve što dispatch rekonstruira iz baze po {@code submissionId}. */
    private record Predmet(SubmissionEntity submission, LessorEntity lessor,
                           AccommodationEntity accommodation, RnEntity rn) {
    }

    public void dispatch(UUID submissionId) {
        Predmet predmet = load(submissionId).orElse(null);
        if (predmet == null) {
            return;
        }
        SubmissionEntity submission = predmet.submission();
        LessorEntity lessor = predmet.lessor();
        AccommodationEntity accommodation = predmet.accommodation();
        String rnValue = predmet.rn().getRn();
        SubmissionPdfContext pdfContext = pdfContext(predmet);

        Documents documents = new Documents(submission, pdfContext, rnValue);
        try {
            EgopFilingService.FilingResult result = egopFilingService.fileRegistration(
                    submission, lessor, documents);
            log.info("egop_dispatch_ok submission={} rn={} filing_number={}",
                    submissionId, rnValue, result.filingNumber());
        } catch (Exception e) {
            handleFailure(submission, pdfContext, rnValue, e);
        }

        // Obavijest ide svakom iznajmljivaču. Non-EU dobiva akt e-mailom (to mu je dostava);
        // s OIB-om samo obavijest bez privitka — akt ide u korisnički pretinac (eGOP strana).
        // Obavijest koja već čeka u redu ponovnog slanja pripada MailRetryJob-u: ponovno
        // urudžbiranje (EgopRetryJob) bi je inače slalo mimo njezinog backoffa i trošilo pokušaje.
        if (mailRetryStore.isQueued(MailRetryEntity.Kind.RN_ISSUED, submissionId)) {
            log.debug("egop_email_skipped reason=queued_for_retry submission={} rn={}", submissionId, rnValue);
            return;
        }
        dispatchEmail(submission, lessor, accommodation, rnValue, documents);
    }

    /**
     * Samo obavijest iznajmljivaču, bez urudžbiranja — za {@link MailRetryJob}, kad je slanje
     * palo. Urudžbiranje ovdje ne smije ponovo krenuti: predmet je možda već urudžbiran
     * ({@code SYNCED}), a njegov ponovni pokušaj vodi {@link EgopRetryJob}.
     *
     * <p>Za povučeni RB obavijest o izdavanju više nema smisla, pa se odustaje.
     */
    public void retryEmail(UUID submissionId) {
        Predmet predmet = load(submissionId).orElse(null);
        if (predmet == null) {
            mailRetryStore.abandon(MailRetryEntity.Kind.RN_ISSUED, submissionId, "missing_entity");
            return;
        }
        if (predmet.rn().getStatus() == RnStatus.WITHDRAWN) {
            mailRetryStore.abandon(MailRetryEntity.Kind.RN_ISSUED, submissionId, "superseded");
            return;
        }
        String rnValue = predmet.rn().getRn();
        dispatchEmail(predmet.submission(), predmet.lessor(), predmet.accommodation(), rnValue,
                new Documents(predmet.submission(), pdfContext(predmet), rnValue));
    }

    private Optional<Predmet> load(UUID submissionId) {
        SubmissionEntity submission = store.findSubmission(submissionId).orElse(null);
        if (submission == null) {
            log.error("egop_dispatch_skipped reason=missing_submission submission={}", submissionId);
            return Optional.empty();
        }
        LessorEntity lessor = lessorRepository.findById(submission.getLessorId()).orElse(null);
        AccommodationEntity accommodation = accommodationRepository.findBySubmissionId(submissionId)
                .stream().findFirst().orElse(null);
        RnEntity rn = rnRepository.findBySubmissionId(submissionId).stream().findFirst().orElse(null);
        if (lessor == null || accommodation == null || rn == null) {
            log.error("egop_dispatch_skipped reason=missing_entity submission={} lessor={} accommodation={} rn={}",
                    submissionId, lessor != null, accommodation != null, rn != null);
            return Optional.empty();
        }
        return Optional.of(new Predmet(submission, lessor, accommodation, rn));
    }

    private SubmissionPdfContext pdfContext(Predmet predmet) {
        return SubmissionPdfContext.of(predmet.accommodation(), predmet.lessor(),
                resolveTypeName(predmet.accommodation().getAccommodationTypeId()),
                predmet.rn().getRn(), null);
    }

    /**
     * Bilježi neuspjeh i sprema PDF zahtjeva bez urudžbenog broja — korisnik mora imati što
     * preuzeti čak i kad urudžbiranje nije prošlo (RN je valjan neovisno o dostavi).
     */
    private void handleFailure(SubmissionEntity submission, SubmissionPdfContext pdfContext,
                               String rnValue, Exception cause) {
        UUID submissionId = submission.getSubmissionId();
        int attemptsSoFar = submission.getEgopSyncAttempts();
        Instant nextAttemptAt = retryPolicy.nextAttemptAt(Instant.now(), attemptsSoFar);
        byte[] pdf = renderPdfSafe(pdfContext, rnValue, submissionId);

        int attempts = store.markFailed(submissionId, cause.getMessage(), nextAttemptAt, pdf);
        if (retryPolicy.isExhausted(attempts)) {
            log.error("egop_retry_exhausted submission={} rn={} attempts={} last_error={}"
                            + " — urudžbiranje se više neće pokušavati automatski",
                    submissionId, rnValue, attempts, cause.getMessage());
        } else {
            log.error("egop_dispatch_failed submission={} rn={} attempt={} next_attempt_at={}: {}",
                    submissionId, rnValue, attempts, nextAttemptAt, cause.getMessage(), cause);
        }
    }

    private void dispatchEmail(SubmissionEntity submission, LessorEntity lessor,
                               AccommodationEntity accommodation, String rn, Documents documents) {
        UUID submissionId = submission.getSubmissionId();
        if (submission.getRnEmailSentAt() != null) {
            log.debug("egop_email_skipped reason=already_sent submission={} rn={}", submissionId, rn);
            mailRetryStore.markSent(MailRetryEntity.Kind.RN_ISSUED, submissionId);
            return;
        }
        if (lessor.getEmail() == null || lessor.getEmail().isBlank()) {
            log.warn("egop_email_skipped reason=no_email lessor={} rn={}", lessor.getLessorId(), rn);
            mailRetryStore.abandon(MailRetryEntity.Kind.RN_ISSUED, submissionId, "no_email");
            return;
        }
        // Non-EU nema korisnički pretinac — mail mu je dostava, pa mora nositi akt. Bez akta se
        // ne šalje: poruka bi tvrdila da je akt u privitku. Ne zamjenjuje se PDF-om zahtjeva,
        // jer to nije akt kojim je RB dodijeljen.
        boolean dostavaMailom = lessor.getLessorOib() == null || lessor.getLessorOib().isBlank();
        byte[] akt = dostavaMailom ? documents.obavijestZaDostavu() : null;
        if (dostavaMailom && akt == null) {
            log.error("egop_email_skipped reason=no_pdf submission={} rn={}", submissionId, rn);
            mailRetryStore.recordFailure(MailRetryEntity.Kind.RN_ISSUED, submissionId, "no_pdf");
            return;
        }
        boolean sent = emailService.sendRnIssuedNotification(new RnIssuedMail(
                lessor.getEmail(), ime(lessor), rn, objekt(accommodation), akt, dostavaMailom));
        // Tek nakon uspješnog slanja — neuspjeh ostaje neoznačen i ide u red ponovnog slanja.
        if (!sent) {
            log.warn("egop_email_not_sent submission={} rn={}", submissionId, rn);
            mailRetryStore.recordFailure(MailRetryEntity.Kind.RN_ISSUED, submissionId, "smtp_failed");
            return;
        }
        submission.markRnEmailSent();
        store.markRnEmailSent(submissionId);
        mailRetryStore.markSent(MailRetryEntity.Kind.RN_ISSUED, submissionId);
    }

    /** Ime fizičke osobe, a za pravnu osobu njezin naziv — isto kao obavijesti o statusu RB-a. */
    private static String ime(LessorEntity lessor) {
        if (lessor.getFirstName() != null && !lessor.getFirstName().isBlank()) {
            return lessor.getFirstName();
        }
        return lessor.getLegalEntityName() == null ? "" : lessor.getLegalEntityName();
    }

    /**
     * „Naziv, Ulica broj, Naselje" od onoga što postoji. Postojeći eTurizam objekt smije doći bez
     * adrese, pa se prazni dijelovi preskaču umjesto da u poruci ostanu viseći zarezi.
     */
    static String objekt(AccommodationEntity accommodation) {
        String ulica = ((accommodation.getStreet() == null ? "" : accommodation.getStreet()) + " "
                + (accommodation.getStreetNumber() == null ? "" : accommodation.getStreetNumber())).strip();
        String mjesto = accommodation.getSettlement() != null && !accommodation.getSettlement().isBlank()
                ? accommodation.getSettlement() : accommodation.getCity();
        String opis = Stream.of(accommodation.getName(), ulica, mjesto)
                .filter(s -> s != null && !s.isBlank())
                .map(String::strip)
                .collect(Collectors.joining(", "));
        return opis.isEmpty() ? "—" : opis;
    }

    private byte[] renderPdfSafe(SubmissionPdfContext pdfContext, String rn, UUID submissionId) {
        try {
            return pdfGenerator.generate(pdfContext.withoutFilingNumber());
        } catch (RuntimeException e) {
            log.error("egop_dispatch_pdf_failed submission={} rn={}: {}", submissionId, rn, e.getMessage(), e);
            return null;
        }
    }

    /**
     * PDF-ovi za dva pismena istog predmeta. Zove se iz {@link EgopFilingService} tek kad
     * pismeno postoji, jer urudžbeni broj mora biti otisnut u dokumentu koji se tom pismenu
     * prilaže.
     *
     * <p>Ako render obavijesti o dodjeli pukne (npr. nedostaje predložak), urudžbiranje se ne
     * ruši — zahtjev je već uložen i valjan, a obavijest pokupi retry. Za razliku od toga,
     * pad PDF-a zahtjeva propagira: bez njega pismeno ostaje bez dokumenta.
     */
    private final class Documents implements EgopDocumentSupplier {

        private final SubmissionEntity submission;
        private final SubmissionPdfContext pdfContext;
        private final String rn;
        /** Obavijest o dodjeli s urudžbenim oznakama, kad ju je urudžbiranje stiglo renderirati. */
        private byte[] obavijest;

        private Documents(SubmissionEntity submission, SubmissionPdfContext pdfContext, String rn) {
            this.submission = submission;
            this.pdfContext = pdfContext;
            this.rn = rn;
        }

        @Override
        public byte[] zahtjev(FilingReference filing) {
            return pdfGenerator.generate(
                    pdfContext.withFilingNumber(EgopFilingService.formatFilingNumber(filing)));
        }

        /**
         * Obavijest o dodjeli za dostavu e-poštom (non-EU). Ista ona koja je priložena izlaznom
         * pismenu, s njegovim URBROJ-em — iz ovog urudžbiranja ili, pri ponovnom slanju, iz
         * spremljenog pismena. Kad izlaznog pismena još nema, renderira se bez URBROJ-a (KLASA
         * sa submissiona, ako je predmet otvoren) — RB je valjan neovisno o urudžbiranju, pa ni
         * dostava ne čeka na njega.
         *
         * @return {@code null} kad se akt ne može renderirati
         */
        byte[] obavijestZaDostavu() {
            if (obavijest != null) {
                return obavijest;
            }
            try {
                FilingReference izlazno = store.findPismeno(submission.getSubmissionId(),
                                StrDocumentType.DODJELA.vrstaPismenaNaziv(),
                                EgopPismenoEntity.ACT_REF_REGISTRACIJA)
                        .filter(p -> p.getUrBroj() != null)
                        .map(p -> new FilingReference(submission.getEgopKlasa(), p.getUrBroj(), p.getJop()))
                        .orElse(null);
                return izlazno != null
                        ? documentService.render(StrDocumentType.DODJELA, rn, null, izlazno)
                        : documentService.render(StrDocumentType.DODJELA, rn, null);
            } catch (RuntimeException e) {
                log.error("egop_dodjela_pdf_failed rn={} (dostava e-poštom): {}", rn, e.getMessage(), e);
                return null;
            }
        }

        @Override
        public byte[] obavijestODodjeli(FilingReference filing) {
            try {
                obavijest = documentService.render(StrDocumentType.DODJELA, rn, null, filing);
                return obavijest;
            } catch (RuntimeException e) {
                log.error("egop_dodjela_pdf_failed rn={} urbroj={}: {} — prilaže se PDF zahtjeva",
                        rn, filing.urBroj(), e.getMessage(), e);
                return pdfGenerator.generate(
                        pdfContext.withFilingNumber(EgopFilingService.formatFilingNumber(filing)));
            }
        }
    }

    private String resolveTypeName(Long accommodationTypeId) {
        if (accommodationTypeId == null) {
            return null;
        }
        return accommodationTypeRepository.findById(accommodationTypeId)
                .map(com.str.backend.lookup.AccommodationTypeEntity::getName)
                .orElse(null);
    }
}
