package com.str.backend.registration.event;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.email.EmailService;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.pdf.SubmissionPdfGenerator;
import com.str.backend.registries.EgopClient;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.request.SubmissionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Routes the issued RN to either eGOP (EU lessor) or e-mail with PDF attachment
 * (non-EU lessor). Runs after the registration transaction has committed — by
 * spec the RN is valid regardless of delivery outcome, so failures here are
 * logged but never rolled back.
 *
 * <p>Non-EU detection: {@code lessor.lessorOib == null}. Non-EU lessors register
 * via {@code LessorEntity.createNonEuRegistration(...)} which does not set an
 * HR OIB (foreign tax number lives in {@code taxNumber}).
 */
@Component
public class RnIssuedListener {

    private static final Logger log = LoggerFactory.getLogger(RnIssuedListener.class);

    private final SubmissionRepository submissionRepository;
    private final AccommodationRepository accommodationRepository;
    private final LessorRepository lessorRepository;
    private final SubmissionPdfGenerator pdfGenerator;
    private final EgopClient egopClient;
    private final EmailService emailService;

    public RnIssuedListener(SubmissionRepository submissionRepository,
                            AccommodationRepository accommodationRepository,
                            LessorRepository lessorRepository,
                            SubmissionPdfGenerator pdfGenerator,
                            EgopClient egopClient,
                            EmailService emailService) {
        this.submissionRepository = submissionRepository;
        this.accommodationRepository = accommodationRepository;
        this.lessorRepository = lessorRepository;
        this.pdfGenerator = pdfGenerator;
        this.egopClient = egopClient;
        this.emailService = emailService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onRnIssued(RnIssuedEvent event) {
        SubmissionEntity submission = submissionRepository.findById(event.submissionId()).orElse(null);
        AccommodationEntity accommodation = accommodationRepository.findById(event.accommodationId()).orElse(null);
        LessorEntity lessor = lessorRepository.findById(event.lessorId()).orElse(null);
        if (submission == null || accommodation == null || lessor == null) {
            log.error("rn_dispatch_skipped reason=missing_entity submission={} accommodation={} lessor={}",
                    event.submissionId(), event.accommodationId(), event.lessorId());
            return;
        }

        boolean nonEu = lessor.getLessorOib() == null;
        if (nonEu) {
            dispatchEmail(event, submission, accommodation, lessor);
        } else {
            dispatchEgop(event, submission, accommodation, lessor);
        }
    }

    private void dispatchEmail(RnIssuedEvent event, SubmissionEntity submission,
                               AccommodationEntity accommodation, LessorEntity lessor) {
        byte[] pdf;
        try {
            pdf = renderPdf(event, accommodation, lessor, null);
        } catch (RuntimeException e) {
            log.error("rn_dispatch_pdf_failed submission={} rn={}: {}",
                    event.submissionId(), event.rn(), e.getMessage(), e);
            return;
        }
        submission.setPdfContent(pdf);
        submissionRepository.save(submission);

        if (lessor.getEmail() == null || lessor.getEmail().isBlank()) {
            log.warn("rn_dispatch_email_skipped reason=no_email lessor={} rn={}",
                    lessor.getLessorId(), event.rn());
            return;
        }
        emailService.sendRnIssuedNotification(lessor.getEmail(), lessor.getFirstName(), event.rn(), pdf);
    }

    private void dispatchEgop(RnIssuedEvent event, SubmissionEntity submission,
                              AccommodationEntity accommodation, LessorEntity lessor) {
        try {
            EgopClient.FilingNumber filing = egopClient.reserveFilingNumber();
            String filingNumber = filing.formatted();
            byte[] pdf = renderPdf(event, accommodation, lessor, filingNumber);
            egopClient.submitFiling(filingNumber, pdf);

            submission.setFilingNumber(filingNumber);
            submission.setDocumentLink("egop://" + filingNumber);
            submission.setPdfContent(pdf);
            submissionRepository.save(submission);
            log.info("rn_dispatch_egop_ok submission={} rn={} filing_number={}",
                    event.submissionId(), event.rn(), filingNumber);
        } catch (RuntimeException e) {
            log.error("rn_dispatch_egop_failed submission={} rn={}: {}",
                    event.submissionId(), event.rn(), e.getMessage(), e);
            // RN remains valid; admin can retry the eGOP filing manually.
        }
    }

    private byte[] renderPdf(RnIssuedEvent event, AccommodationEntity accommodation,
                             LessorEntity lessor, String filingNumber) {
        return pdfGenerator.generate(
                accommodation.getName(),
                accommodation.getStreet(),
                accommodation.getStreetNumber(),
                event.postalCode(),
                accommodation.getCity(),
                accommodation.getMaxBeds(),
                event.countyName(),
                lessor,
                filingNumber,
                event.rn(),
                event.typeName());
    }
}
