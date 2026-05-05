package com.str.backend.registration;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.auth.AuthContext;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.exception.ValidationRejectedException;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.pdf.SubmissionPdfGenerator;
import com.str.backend.registration.dto.RegistrationRequest;
import com.str.backend.registration.dto.RegistrationResponse;
import com.str.backend.registries.EgopClient;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnService;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.request.SubmissionRepository;
import com.str.backend.str.StrLessorLookupService;
import com.str.backend.validation.ParallelValidationOrchestrator;
import com.str.backend.validation.PipelineResult;
import com.str.backend.validation.ValidationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);
    private final LessorRepository lessorRepository;
    private final AccommodationRepository accommodationRepository;
    private final SubmissionRepository submissionRepository;
    private final ParallelValidationOrchestrator orchestrator;
    private final RnService rnService;
    private final EgopClient egopClient;
    private final SubmissionPdfGenerator pdfGenerator;
    private final AuthContext authContext;
    private final StrLessorLookupService strLessorLookupService;

    public RegistrationService(LessorRepository lessorRepository,
                               AccommodationRepository accommodationRepository,
                               SubmissionRepository submissionRepository,
                               ParallelValidationOrchestrator orchestrator,
                               RnService rnService,
                               EgopClient egopClient,
                               SubmissionPdfGenerator pdfGenerator,
                               AuthContext authContext,
                               StrLessorLookupService strLessorLookupService) {
        this.lessorRepository = lessorRepository;
        this.accommodationRepository = accommodationRepository;
        this.submissionRepository = submissionRepository;
        this.orchestrator = orchestrator;
        this.rnService = rnService;
        this.egopClient = egopClient;
        this.pdfGenerator = pdfGenerator;
        this.authContext = authContext;
        this.strLessorLookupService = strLessorLookupService;
    }

    @Transactional(noRollbackFor = ValidationRejectedException.class)
    public RegistrationResponse generateRegistrationNumber(RegistrationRequest req) {
        AuthContext.AuthenticatedUser user = authContext.currentUser();
        LessorEntity lessor = strLessorLookupService.resolveLessor(user);

        AccommodationEntity accommodation = buildAccommodation(req);

        ValidationContext context = new ValidationContext(accommodation, lessor);
        PipelineResult result = orchestrator.execute(context);
        if (result.getOutcome() == PipelineResult.Outcome.REJECTED) {
            throw new ValidationRejectedException(result.getStep(), result.getDetail());
        }

        byte[] draftPdf = pdfGenerator.generate(req, lessor, null);
        EgopClient.UrudzbeniBroj filing = egopClient.rezervirajUrudzbeniBroj();
        byte[] finalPdf = pdfGenerator.generate(req, lessor, filing.formatiran());
        EgopClient.PotvrdaUrudzbiranja confirmation =
                egopClient.posaljiZahtjev(filing.formatiran(), finalPdf);

        log.info("egop_filing_ok filingNumber={} draft_size={} final_size={}",
                confirmation.urudzbeniBroj(), draftPdf.length, finalPdf.length);

        lessorRepository.save(lessor);
        SubmissionEntity submission = SubmissionEntity.create(
                confirmation.urudzbeniBroj(),
                lessor.getLessorId(),
                null,
                confirmation.datumPotvrde(),
                "egop://" + confirmation.urudzbeniBroj(),
                finalPdf);
        submissionRepository.save(submission);

        accommodation.linkToSubmission(submission.getSubmissionId());
        accommodationRepository.save(accommodation);

        RnEntity rn = rnService.issue(submission.getSubmissionId(), accommodation.getAccommodationId());

        log.info("registration_success lessor={} submission={}", lessor.getLessorId(), submission.getSubmissionId());
        return new RegistrationResponse(lessor.getLessorId(),
                List.of(new RegistrationResponse.AssignedRb(accommodation.getAccommodationId(), rn.getRn())));
    }

    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> getPdfContent(UUID submissionId) {
        SubmissionEntity submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ResourceNotFoundException("submission not found: " + submissionId));
        byte[] pdf = submission.getPdfContent();
        if (pdf == null || pdf.length == 0) {
            throw new ResourceNotFoundException("error.pdf.not.stored");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"submission-" + submission.getFilingNumber().replace('/', '_') + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    private AccommodationEntity buildAccommodation(RegistrationRequest req) {
        AccommodationEntity entity = AccommodationEntity.create(
                null, req.getCounty().name(), req.getCityId(), req.getStreet(), req.getStreetNumber(),
                req.getMaxBeds(), req.getMaxGuests(), req.getOfferType(), false, false, true);
        entity.setName(req.getName());
        entity.setSettlement(req.getSettlementId());
        if (req.getTypeId() != null) {
            try {
                entity.setAccommodationTypeId(Long.parseLong(req.getTypeId()));
            } catch (NumberFormatException ignored) {
            }
        }
        return entity;
    }
}
