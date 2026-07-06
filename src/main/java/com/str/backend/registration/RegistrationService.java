package com.str.backend.registration;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.address.CountyEntity;
import com.str.backend.address.CountyRepository;
import com.str.backend.address.MunicipalityEntity;
import com.str.backend.address.MunicipalityRepository;
import com.str.backend.address.SettlementEntity;
import com.str.backend.address.SettlementRepository;
import com.str.backend.exception.DuplicateLocationException;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.exception.ValidationRejectedException;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.registration.dto.AccommodationRequest;
import com.str.backend.registration.dto.RegistrationExternalRequest;
import com.str.backend.registration.dto.RegistrationRequest;
import com.str.backend.registration.dto.RegistrationResponse;
import com.str.backend.registration.event.RnIssuedEvent;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnRepository;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;

@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);
    private final LessorRepository lessorRepository;
    private final AccommodationRepository accommodationRepository;
    private final SubmissionRepository submissionRepository;
    private final ParallelValidationOrchestrator orchestrator;
    private final RnService rnService;
    private final RnRepository rnRepository;
    private final StrLessorLookupService strLessorLookupService;
    private final CountyRepository countyRepository;
    private final MunicipalityRepository municipalityRepository;
    private final SettlementRepository settlementRepository;
    private final AccommodationTypeRepository accommodationTypeRepository;
    private final ApplicationEventPublisher eventPublisher;

    public RegistrationService(LessorRepository lessorRepository,
                               AccommodationRepository accommodationRepository,
                               SubmissionRepository submissionRepository,
                               ParallelValidationOrchestrator orchestrator,
                               RnService rnService,
                               RnRepository rnRepository,
                               StrLessorLookupService strLessorLookupService,
                               CountyRepository countyRepository,
                               MunicipalityRepository municipalityRepository,
                               SettlementRepository settlementRepository,
                               AccommodationTypeRepository accommodationTypeRepository,
                               ApplicationEventPublisher eventPublisher) {
        this.lessorRepository = lessorRepository;
        this.accommodationRepository = accommodationRepository;
        this.submissionRepository = submissionRepository;
        this.orchestrator = orchestrator;
        this.rnService = rnService;
        this.rnRepository = rnRepository;
        this.strLessorLookupService = strLessorLookupService;
        this.countyRepository = countyRepository;
        this.municipalityRepository = municipalityRepository;
        this.settlementRepository = settlementRepository;
        this.accommodationTypeRepository = accommodationTypeRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(noRollbackFor = ValidationRejectedException.class)
    public RegistrationResponse generateRegistrationNumber(RegistrationRequest req) {
        CountyEntity county = countyRepository.findById(req.countyId())
                .orElseThrow(() -> new ResourceNotFoundException("county not found: " + req.countyId()));

        checkDuplicateLocation(req.oib(), req.houseNumberCode(), req.confirmDuplicateLocation());

        LessorEntity lessor = strLessorLookupService.resolveLessor(req.oib());
        AccommodationEntity accommodation = buildAccommodation(req, county.getName());
        runValidation(accommodation, lessor);

        return commitRegistration(lessor, accommodation, county.getName(),
                resolveTypeName(req.typeId()), req.postalCode());
    }

    @Transactional(noRollbackFor = ValidationRejectedException.class)
    public RegistrationResponse generateRegistrationNumberExternal(RegistrationExternalRequest req, UUID lessorId) {
        CountyEntity county = countyRepository.findById(req.countyId())
                .orElseThrow(() -> new ResourceNotFoundException("county not found: " + req.countyId()));

        LessorEntity lessor = lessorRepository.findById(lessorId)
                .orElseThrow(() -> new ResourceNotFoundException("lessor not found: " + lessorId));
        checkDuplicateLocation(lessor.getLessorOib(), req.houseNumberCode(), req.confirmDuplicateLocation());

        AccommodationEntity accommodation = buildAccommodation(req, county.getName());
        runValidation(accommodation, lessor);

        return commitRegistration(lessor, accommodation, county.getName(),
                resolveTypeName(req.typeId()), req.postalCode());
    }

    private void checkDuplicateLocation(String oib, String houseNumberCode, Boolean confirmed) {
        if (oib == null || houseNumberCode == null || houseNumberCode.isBlank()) return;
        if (Boolean.TRUE.equals(confirmed)) return;
        rnRepository.findActiveOrSuspendedRnByOibAndHouseNumberCode(oib, houseNumberCode)
                .stream().findFirst()
                .ifPresent(rn -> { throw new DuplicateLocationException(rn); });
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

    AccommodationEntity buildAccommodation(AccommodationRequest req, String countyName) {
        String cityName = resolveEntityName(req.cityId(), municipalityRepository, MunicipalityEntity::getName, "");
        String settlementName = resolveEntityName(req.settlementId(), settlementRepository, SettlementEntity::getName, null);
        AccommodationEntity entity = AccommodationEntity.create(
                null, countyName, cityName, req.street(), req.streetNumber(),
                req.maxBeds(), req.maxGuests(), req.offerType(), req.offering(),
                req.building(), req.apartments(), req.legalized());
        entity.setName(req.name());
        entity.setSettlement(settlementName);
        entity.setHouseNumberCode(req.houseNumberCode());
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

    private void runValidation(AccommodationEntity accommodation, LessorEntity lessor) {
        ValidationContext context = new ValidationContext(accommodation, lessor);
        PipelineResult result = orchestrator.execute(context);
        if (result.getOutcome() == PipelineResult.Outcome.REJECTED) {
            throw new ValidationRejectedException(result.getStep(), result.getDetail());
        }
    }

    private RegistrationResponse commitRegistration(LessorEntity lessor, AccommodationEntity accommodation,
                                                    String countyName, String typeName, String postalCode) {
        lessorRepository.save(lessor);

        // RN is issued before PDF/eGOP/e-mail. filing_number stays null — it
        // will be populated asynchronously after eGOP confirms, or stay null
        // on the non-EU e-mail path.
        SubmissionEntity submission = SubmissionEntity.create(
                null,
                lessor.getLessorId(),
                null,
                Instant.now(),
                null,
                null);
        submissionRepository.save(submission);

        accommodation.linkToSubmission(submission.getSubmissionId());
        accommodationRepository.save(accommodation);

        RnEntity rn = rnService.issue(submission.getSubmissionId(), accommodation.getAccommodationId());

        eventPublisher.publishEvent(new RnIssuedEvent(
                submission.getSubmissionId(),
                accommodation.getAccommodationId(),
                lessor.getLessorId(),
                rn.getRn(),
                countyName,
                typeName,
                postalCode));

        log.info("registration_success lessor={} submission={} rn={}",
                lessor.getLessorId(), submission.getSubmissionId(), rn.getRn());
        return new RegistrationResponse(rn.getRn(), submission.getSubmissionId());
    }

    private <T> String resolveEntityName(String id, JpaRepository<T, Long> repository,
                                          Function<T, String> nameExtractor, String nullDefault) {
        if (id == null) return nullDefault;
        try {
            return repository.findById(Long.parseLong(id))
                    .map(nameExtractor)
                    .orElse(id);
        } catch (NumberFormatException ignored) {
            return id;
        }
    }

    private String resolveTypeName(String typeId) {
        if (typeId == null) return null;
        try {
            return accommodationTypeRepository.findById(Long.parseLong(typeId))
                    .map(AccommodationTypeEntity::getName)
                    .orElse(null);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
