package com.str.backend.registration;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.address.CountyEntity;
import com.str.backend.address.CountyRepository;
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
import com.str.backend.lookup.AccommodationTypeEntity;
import com.str.backend.lookup.AccommodationTypeRepository;
import com.str.backend.str.StrLessorLookupService;
import com.str.backend.validation.ParallelValidationOrchestrator;
import com.str.backend.validation.PipelineResult;
import com.str.backend.validation.ValidationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final StrLessorLookupService strLessorLookupService;
    private final CountyRepository countyRepository;
    private final AccommodationTypeRepository accommodationTypeRepository;

    public RegistrationService(LessorRepository lessorRepository,
                               AccommodationRepository accommodationRepository,
                               SubmissionRepository submissionRepository,
                               ParallelValidationOrchestrator orchestrator,
                               RnService rnService,
                               EgopClient egopClient,
                               SubmissionPdfGenerator pdfGenerator,
                               StrLessorLookupService strLessorLookupService,
                               CountyRepository countyRepository,
                               AccommodationTypeRepository accommodationTypeRepository) {
        this.lessorRepository = lessorRepository;
        this.accommodationRepository = accommodationRepository;
        this.submissionRepository = submissionRepository;
        this.orchestrator = orchestrator;
        this.rnService = rnService;
        this.egopClient = egopClient;
        this.pdfGenerator = pdfGenerator;
        this.strLessorLookupService = strLessorLookupService;
        this.countyRepository = countyRepository;
        this.accommodationTypeRepository = accommodationTypeRepository;
    }

    @Transactional(noRollbackFor = ValidationRejectedException.class)
    public RegistrationResponse generateRegistrationNumber(RegistrationRequest req) {
        CountyEntity county = countyRepository.findById(req.countyId())
                .orElseThrow(() -> new ResourceNotFoundException("county not found: " + req.countyId()));

        LessorEntity lessor = strLessorLookupService.resolveLessor(req.oib());

        AccommodationEntity accommodation = buildAccommodation(req, county.getName());

        ValidationContext context = new ValidationContext(accommodation, lessor);
        PipelineResult result = orchestrator.execute(context);
        if (result.getOutcome() == PipelineResult.Outcome.REJECTED) {
            throw new ValidationRejectedException(result.getStep(), result.getDetail());
        }

        String typeName = resolveTypeName(req.typeId());
        byte[] draftPdf = pdfGenerator.generate(req, county.getName(), lessor, null, typeName);
        EgopClient.FilingNumber filing = egopClient.reserveFilingNumber();
        byte[] finalPdf = pdfGenerator.generate(req, county.getName(), lessor, filing.formatted(), typeName);
        EgopClient.FilingConfirmation confirmation =
                egopClient.submitFiling(filing.formatted(), finalPdf);

        log.info("egop_filing_ok filingNumber={} draft_size={} final_size={}",
                confirmation.filingNumber(), draftPdf.length, finalPdf.length);

        lessorRepository.save(lessor);
        SubmissionEntity submission = SubmissionEntity.create(
                confirmation.filingNumber(),
                lessor.getLessorId(),
                null,
                confirmation.confirmedAt(),
                "egop://" + confirmation.filingNumber(),
                finalPdf);
        submissionRepository.save(submission);

        accommodation.linkToSubmission(submission.getSubmissionId());
        accommodationRepository.save(accommodation);

        RnEntity rn = rnService.issue(submission.getSubmissionId(), accommodation.getAccommodationId());

        log.info("registration_success lessor={} submission={}", lessor.getLessorId(), submission.getSubmissionId());
        return new RegistrationResponse(rn.getRn(), submission.getSubmissionId());
    }

    @Transactional(readOnly = true)
    public SubmissionEntity getSubmissionForPdf(UUID submissionId) {
        SubmissionEntity submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ResourceNotFoundException("submission not found: " + submissionId));
        if (submission.getPdfContent() == null || submission.getPdfContent().length == 0) {
            throw new ResourceNotFoundException("error.pdf.not.stored");
        }
        return submission;
    }

    AccommodationEntity buildAccommodation(RegistrationRequest req, String countyName) {
        AccommodationEntity entity = AccommodationEntity.create(
                null, countyName, req.cityId(), req.street(), req.streetNumber(),
                req.maxBeds(), req.maxGuests(), req.offerType(), req.offering(),
                req.building(), req.apartments(), req.legalized());
        entity.setName(req.name());
        entity.setSettlement(req.settlementId());
        entity.setFloor(req.floor());
        entity.setLessorResidence(req.lessorResidence());
        entity.setConsent(req.coOwnerConsent(), req.consentDate(), req.consentWithdrawalDate());
        if (req.host() != null) {
            entity.markHost(req.host());
        }
        if (req.typeId() != null) {
            try {
                entity.setAccommodationTypeId(Long.parseLong(req.typeId()));
            } catch (NumberFormatException e) {
                log.warn("typeId '{}' nije numerički, polje se ignorira", req.typeId());
            }
        }
        return entity;
    }

    private String resolveTypeName(String typeId) {
        if (typeId == null) return null;
        try {
            return accommodationTypeRepository.findById(Long.parseLong(typeId))
                    .map(AccommodationTypeEntity::getName)
                    .orElse(null);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
