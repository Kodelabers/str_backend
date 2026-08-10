package com.str.backend.registration;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.address.CountyEntity;
import com.str.backend.address.CountyRepository;
import com.str.backend.address.MunicipalityEntity;
import com.str.backend.address.MunicipalityRepository;
import com.str.backend.address.SettlementEntity;
import com.str.backend.address.SettlementRepository;
import com.str.backend.exception.BusinessException;
import com.str.backend.exception.DuplicateLocationException;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.exception.ValidationRejectedException;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.lookup.AccommodationTypeRepository;
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
import com.str.backend.str.FacilityClaimVerifier;
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
import java.util.Optional;
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
    private final FacilityClaimVerifier facilityClaimVerifier;
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
                               FacilityClaimVerifier facilityClaimVerifier,
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
        this.facilityClaimVerifier = facilityClaimVerifier;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(noRollbackFor = ValidationRejectedException.class)
    public RegistrationResponse generateRegistrationNumber(RegistrationRequest req) {
        CountyEntity county = countyRepository.findById(req.countyId())
                .orElseThrow(() -> new ResourceNotFoundException("county not found: " + req.countyId()));

        AccommodationEntity accommodation = buildAccommodation(req, county.getName());
        verifyFacilityClaim(req.oib(), accommodation);
        checkDuplicateLocation(req.oib(), accommodation, req.confirmDuplicateLocation());

        LessorEntity lessor = strLessorLookupService.resolveLessor(req.oib());
        runValidation(accommodation, lessor);

        return commitRegistration(lessor, accommodation);
    }

    @Transactional(noRollbackFor = ValidationRejectedException.class)
    public RegistrationResponse generateRegistrationNumberExternal(RegistrationExternalRequest req, UUID lessorId) {
        CountyEntity county = countyRepository.findById(req.countyId())
                .orElseThrow(() -> new ResourceNotFoundException("county not found: " + req.countyId()));

        LessorEntity lessor = lessorRepository.findById(lessorId)
                .orElseThrow(() -> new ResourceNotFoundException("lessor not found: " + lessorId));

        AccommodationEntity accommodation = buildAccommodation(req, county.getName());
        verifyFacilityClaim(lessor.getLessorOib(), accommodation);
        checkDuplicateLocation(lessor.getLessorOib(), accommodation, req.confirmDuplicateLocation());

        runValidation(accommodation, lessor);

        return commitRegistration(lessor, accommodation);
    }

    /**
     * Kad zahtjev nosi {@code facilityId} iz tuStart handoffa, objekt mora pripadati podnositelju
     * i poslana vrsta / broj kreveta moraju odgovarati eTurizmu — v. {@link FacilityClaimVerifier}.
     * Provjera ide prije svega ostalog: jedina je brana između tuđeg {@code facilityId} i
     * write-backa RB-a u tuđi zapis.
     */
    private void verifyFacilityClaim(String oib, AccommodationEntity accommodation) {
        facilityClaimVerifier.verify(oib, accommodation.getFacilityId(),
                accommodation.getAccommodationTypeId(), accommodation.getMaxBeds());
    }

    /**
     * Surfaces a still-standing (ACTIVE/SUSPENSION_PROPOSED/SUSPENDED) RN that already covers
     * this address so the FE can prompt
     * for explicit confirmation (and only then proceed). Match is on the full address tuple
     * (county + city + street + streetNumber); when OIB is known it additionally narrows to
     * the same lessor, which is the common case. The house-number šifra used to be the sole
     * key but doesn't survive flows where it's missing — the address tuple does.
     */
    private void checkDuplicateLocation(String oib, AccommodationEntity accommodation, Boolean confirmed) {
        if (Boolean.TRUE.equals(confirmed)) return;
        if (isBlank(accommodation.getStreet()) || isBlank(accommodation.getStreetNumber())
                || isBlank(accommodation.getCity()) || isBlank(accommodation.getCounty())) {
            return;
        }
        rnRepository.findActiveOrSuspendedRnByAddressAndOib(
                        accommodation.getCounty(),
                        accommodation.getCity(),
                        accommodation.getStreet(),
                        accommodation.getStreetNumber(),
                        isBlank(oib) ? null : oib)
                .stream().findFirst()
                .ifPresent(rn -> { throw new DuplicateLocationException(rn); });
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
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
        entity.setFacilityId(req.facilityId());
        entity.setSettlement(settlementName);
        entity.setHouseNumberCode(req.houseNumberCode());
        entity.setPostalCode(req.postalCode());
        entity.setFloor(req.floor());
        entity.setLessorResidence(req.lessorResidence());
        entity.setConsent(req.coOwnerConsent(), req.consentDate(), req.consentWithdrawalDate());
        if (req.host() != null) {
            entity.markHost(req.host());
        }
        resolveAccommodationTypeId(req.typeId()).ifPresent(entity::setAccommodationTypeId);
        return entity;
    }

    /**
     * Prihvaća i numerički {@code type_id} i stabilnu šifru vrste ({@code FS_KUCA_ZA_ODMOR},
     * ...). Šifra je ono što tuStart šalje u handoff URL-u i ono na što se veže frontend,
     * jer se {@code type_id} razlikuje među okolinama.
     *
     * <p>Nerazrješiva vrsta se ne ignorira tiho: bez nje registracija gubi provjeru iz
     * {@code RnService.issue()} koja hotelu/kampu brani dodjelu RB-a, pa bi objekt bez
     * prava na RB prošao. Zato {@link BusinessException} (→ 400) umjesto praznog polja.
     */
    private Optional<Long> resolveAccommodationTypeId(String typeId) {
        if (typeId == null || typeId.isBlank()) {
            return Optional.empty();
        }
        String value = typeId.trim();

        if (value.chars().allMatch(Character::isDigit)) {
            long id = Long.parseLong(value);
            if (!accommodationTypeRepository.existsById(id)) {
                throw new BusinessException("error.accommodation.type.unknown");
            }
            return Optional.of(id);
        }

        return Optional.of(accommodationTypeRepository.findByCodeIgnoreCase(value)
                .orElseThrow(() -> {
                    log.warn("nepoznata vrsta smještaja '{}' — nije ni type_id ni šifra", value);
                    return new BusinessException("error.accommodation.type.unknown");
                })
                .getTypeId());
    }

    private void runValidation(AccommodationEntity accommodation, LessorEntity lessor) {
        ValidationContext context = new ValidationContext(accommodation, lessor);
        PipelineResult result = orchestrator.execute(context);
        if (result.getOutcome() == PipelineResult.Outcome.REJECTED) {
            throw new ValidationRejectedException(result.getStep(), result.getDetail());
        }
    }

    private RegistrationResponse commitRegistration(LessorEntity lessor, AccommodationEntity accommodation) {
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

        eventPublisher.publishEvent(new RnIssuedEvent(submission.getSubmissionId(), rn.getRn()));

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

}
