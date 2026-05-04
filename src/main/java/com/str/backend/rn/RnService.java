package com.str.backend.rn;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;
import com.str.backend.domain.RegistrationNumber;
import com.str.backend.exception.BusinessException;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.lookup.AccommodationTypeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class RnService {

    private static final Logger log = LoggerFactory.getLogger(RnService.class);
    private static final int MAX_RN_ATTEMPTS = 5;

    private final RnRepository repository;
    private final RnStatusTransitionService transitionService;
    private final AccommodationRepository accommodationRepository;
    private final AccommodationTypeRepository accommodationTypeRepository;
    private final Clock clock;

    public RnService(RnRepository repository, RnStatusTransitionService transitionService,
                     AccommodationRepository accommodationRepository,
                     AccommodationTypeRepository accommodationTypeRepository, Clock clock) {
        this.repository = repository;
        this.transitionService = transitionService;
        this.accommodationRepository = accommodationRepository;
        this.accommodationTypeRepository = accommodationTypeRepository;
        this.clock = clock;
    }

    @Transactional
    public RnEntity issue(UUID submissionId, UUID accommodationId) {
        // STR spec §2.1: Hotel/kamp (registrationNumberAllowed = false) must not receive RN.
        AccommodationEntity accommodation = accommodationRepository.findById(accommodationId)
                .orElseThrow(() -> new ResourceNotFoundException("accommodation not found: " + accommodationId));
        if (accommodation.getAccommodationTypeId() != null) {
            accommodationTypeRepository.findById(accommodation.getAccommodationTypeId()).ifPresent(type -> {
                if (!type.isRegistrationNumberAllowed()) {
                    throw new BusinessException("error.rn.type.not.allowed");
                }
            });
        }
        LocalDate today = LocalDate.now(clock);
        for (int i = 0; i < MAX_RN_ATTEMPTS; i++) {
            String candidate = RegistrationNumber.generate().getValue();
            if (!repository.existsByRn(candidate)) {
                RnEntity rn = RnEntity.issue(candidate, submissionId, accommodationId, today);
                repository.save(rn);
                log.info("rn_issued rn={} submission={} accommodation={}", candidate, submissionId, accommodationId);
                return rn;
            }
        }
        throw new BusinessException("error.rn.generation.failed");
    }

    @Transactional
    public RnEntity suspend(String rn, RnTrigger trigger) {
        if (trigger != RnTrigger.CONSENT_EXPIRY && trigger != RnTrigger.INSPECTION) {
            throw new BusinessException("error.rn.suspend.trigger.invalid");
        }
        RnEntity e = load(rn);
        transitionService.transition(e, RnStatus.SUSPENDED, trigger);
        return e;
    }

    @Transactional
    public RnEntity reactivate(String rn) {
        RnEntity e = load(rn);
        transitionService.transition(e, RnStatus.ACTIVE, RnTrigger.REACTIVATE);
        return e;
    }

    @Transactional
    public RnEntity withdraw(String rn) {
        RnEntity e = load(rn);
        transitionService.transition(e, RnStatus.WITHDRAWN, RnTrigger.WITHDRAWAL);
        return e;
    }

    @Transactional(readOnly = true)
    public RnEntity find(String rn) {
        return load(rn);
    }

    @Transactional(readOnly = true)
    public List<RnEntity> forAccommodation(UUID accommodationId) {
        return repository.findByAccommodationId(accommodationId);
    }

    /** STR-1.5: list of inactive RNs (SUSPENDED + WITHDRAWN), for display to competent authority. */
    @Transactional(readOnly = true)
    public List<RnEntity> inactive() {
        return repository.findByStatusInOrderByUpdatedAtDesc(List.of(RnStatus.SUSPENDED, RnStatus.WITHDRAWN));
    }

    private RnEntity load(String rn) {
        return repository.findById(rn)
                .orElseThrow(() -> new ResourceNotFoundException("rn not found: " + rn));
    }
}
