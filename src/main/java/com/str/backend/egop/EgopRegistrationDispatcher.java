package com.str.backend.egop;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.document.FilingReference;
import com.str.backend.document.StrDocumentService;
import com.str.backend.document.StrDocumentType;
import com.str.backend.email.EmailService;
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
import java.util.UUID;

/**
 * Urudžbira jednu registraciju u eGOP i (za non-EU) šalje obavijest e-mailom.
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
 * <p>Dostava e-mailom je idempotentna preko {@code submission.rn_email_sent_at} —
 * bez toga bi svaki retry non-EU iznajmljivaču poslao još jedan identičan mail.
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

    public EgopRegistrationDispatcher(EgopFilingStore store,
                                      AccommodationRepository accommodationRepository,
                                      LessorRepository lessorRepository,
                                      RnRepository rnRepository,
                                      AccommodationTypeRepository accommodationTypeRepository,
                                      SubmissionPdfGenerator pdfGenerator,
                                      StrDocumentService documentService,
                                      EgopFilingService egopFilingService,
                                      EgopRetryPolicy retryPolicy,
                                      EmailService emailService) {
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
    }

    public void dispatch(UUID submissionId) {
        SubmissionEntity submission = store.findSubmission(submissionId).orElse(null);
        if (submission == null) {
            log.error("egop_dispatch_skipped reason=missing_submission submission={}", submissionId);
            return;
        }
        LessorEntity lessor = lessorRepository.findById(submission.getLessorId()).orElse(null);
        AccommodationEntity accommodation = accommodationRepository.findBySubmissionId(submissionId)
                .stream().findFirst().orElse(null);
        RnEntity rn = rnRepository.findBySubmissionId(submissionId).stream().findFirst().orElse(null);
        if (lessor == null || accommodation == null || rn == null) {
            log.error("egop_dispatch_skipped reason=missing_entity submission={} lessor={} accommodation={} rn={}",
                    submissionId, lessor != null, accommodation != null, rn != null);
            return;
        }

        String rnValue = rn.getRn();
        SubmissionPdfContext pdfContext = SubmissionPdfContext.of(accommodation, lessor,
                resolveTypeName(accommodation.getAccommodationTypeId()), rnValue, null);

        byte[] pdf;
        try {
            EgopFilingService.FilingResult result = egopFilingService.fileRegistration(
                    submission, lessor, new Documents(pdfContext, rnValue));
            pdf = result.zahtjevPdf();
            log.info("egop_dispatch_ok submission={} rn={} filing_number={}",
                    submissionId, rnValue, result.filingNumber());
        } catch (Exception e) {
            pdf = handleFailure(submission, pdfContext, rnValue, e);
        }

        // Dostava: non-EU dobiva PDF e-mailom; EU ide preko KP eGrađana (eGOP strana).
        if (lessor.getLessorOib() == null) {
            dispatchEmail(submission, lessor, rnValue, pdf);
        }
    }

    /**
     * Bilježi neuspjeh i vraća PDF bez urudžbenog broja — korisnik mora imati što
     * preuzeti čak i kad urudžbiranje nije prošlo (RN je valjan neovisno o dostavi).
     */
    private byte[] handleFailure(SubmissionEntity submission, SubmissionPdfContext pdfContext,
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
        return pdf;
    }

    private void dispatchEmail(SubmissionEntity submission, LessorEntity lessor, String rn, byte[] pdf) {
        UUID submissionId = submission.getSubmissionId();
        if (submission.getRnEmailSentAt() != null) {
            log.debug("egop_email_skipped reason=already_sent submission={} rn={}", submissionId, rn);
            return;
        }
        if (pdf == null) {
            log.error("egop_email_skipped reason=no_pdf submission={} rn={}", submissionId, rn);
            return;
        }
        if (lessor.getEmail() == null || lessor.getEmail().isBlank()) {
            log.warn("egop_email_skipped reason=no_email lessor={} rn={}", lessor.getLessorId(), rn);
            return;
        }
        emailService.sendRnIssuedNotification(lessor.getEmail(), lessor.getFirstName(), rn, pdf);
        // Tek nakon uspješnog slanja — ako pukne, sljedeći retry pokušava ponovo.
        submission.markRnEmailSent();
        store.markRnEmailSent(submissionId);
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

        private final SubmissionPdfContext pdfContext;
        private final String rn;

        private Documents(SubmissionPdfContext pdfContext, String rn) {
            this.pdfContext = pdfContext;
            this.rn = rn;
        }

        @Override
        public byte[] zahtjev(FilingReference filing) {
            return pdfGenerator.generate(
                    pdfContext.withFilingNumber(EgopFilingService.formatFilingNumber(filing)));
        }

        @Override
        public byte[] obavijestODodjeli(FilingReference filing) {
            try {
                return documentService.render(StrDocumentType.DODJELA, rn, null, filing);
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
