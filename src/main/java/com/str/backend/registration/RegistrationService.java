package com.str.backend.registration;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
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
import com.str.backend.validation.ParallelValidationOrchestrator;
import com.str.backend.validation.PipelineResult;
import com.str.backend.validation.ValidationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);
    private static final String TYPE_NEW_REGISTRATION = "NOVA_REGISTRACIJA";

    private final LessorRepository lessorRepository;
    private final AccommodationRepository accommodationRepository;
    private final SubmissionRepository submissionRepository;
    private final ParallelValidationOrchestrator orchestrator;
    private final RnService rnService;
    private final EgopClient egopClient;
    private final SubmissionPdfGenerator pdfGenerator;

    public RegistrationService(LessorRepository lessorRepository,
                               AccommodationRepository accommodationRepository,
                               SubmissionRepository submissionRepository,
                               ParallelValidationOrchestrator orchestrator,
                               RnService rnService,
                               EgopClient egopClient,
                               SubmissionPdfGenerator pdfGenerator) {
        this.lessorRepository = lessorRepository;
        this.accommodationRepository = accommodationRepository;
        this.submissionRepository = submissionRepository;
        this.orchestrator = orchestrator;
        this.rnService = rnService;
        this.egopClient = egopClient;
        this.pdfGenerator = pdfGenerator;
    }

    @Transactional(noRollbackFor = ValidationRejectedException.class)
    public RegistrationResponse generateRegistrationNumber(RegistrationRequest req) {
        // Stub lessor — real implementation will resolve from authenticated user session
        LessorEntity lessor = LessorEntity.create("N/A", "N/A", req.getStreet(), req.getStreetNumber(),
                req.getCityId(), req.getCountyId(), "noreply@str.hr");

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
                TYPE_NEW_REGISTRATION,
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

    private AccommodationEntity buildAccommodation(RegistrationRequest req) {
        boolean apartments = req.isBuilding() && req.getApartmentCount() != null && req.getApartmentCount() > 1;

        AccommodationEntity entity = AccommodationEntity.create(
                null, req.getCountyId(), req.getCityId(), req.getStreet(), req.getStreetNumber(),
                req.getMaxBeds(), req.getMaxGuests(), req.getOfferType(), req.isBuilding(), apartments, req.isLegalized());
        entity.setName(req.getName());
        entity.setSettlement(req.getSettlementId());
        entity.setFloor(req.getFloor() != null ? String.valueOf(req.getFloor()) : null);
        if (Boolean.TRUE.equals(req.getCoOwnerConsent()) || req.getConsentDate() != null) {
            entity.setConsent(req.getCoOwnerConsent(), req.getConsentDate(), null);
        }
        return entity;
    }
}
