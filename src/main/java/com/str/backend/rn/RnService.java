package com.str.backend.rn;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.common.SearchTokens;
import com.str.backend.common.Strings;
import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;
import com.str.backend.domain.RegistrationNumber;
import com.str.backend.exception.BusinessException;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.lookup.AccommodationTypeRepository;
import com.str.backend.rn.dto.RnDetailDto;
import com.str.backend.rn.dto.RnSummaryDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class RnService {

    private static final Logger log = LoggerFactory.getLogger(RnService.class);
    private static final int MAX_RN_ATTEMPTS = 5;

    // Maps str.county.name → EGOP organization ID used in RN generation
    private static final Map<String, Integer> COUNTY_EGOP_ORG_IDS = Map.ofEntries(
            Map.entry("Zagrebačka županija",                    2),
            Map.entry("Krapinsko-zagorska županija",            3),
            Map.entry("Sisačko-moslavačka županija",            4),
            Map.entry("Karlovačka županija",                    5),
            Map.entry("Varaždinska županija",                   6),
            Map.entry("Koprivničko-križevačka županija",        7),
            Map.entry("Bjelovarsko-bilogorska županija",        8),
            Map.entry("Primorsko-goranska županija",            9),
            Map.entry("Ličko-senjska županija",                10),
            Map.entry("Virovitičko-podravska županija",        11),
            Map.entry("Požeško-slavonska županija",            12),
            Map.entry("Brodsko-posavska županija",             13),
            Map.entry("Zadarska županija",                     14),
            Map.entry("Osječko-baranjska županija",            15),
            Map.entry("Šibensko-kninska županija",             16),
            Map.entry("Vukovarsko-srijemska županija",         17),
            Map.entry("Splitsko-dalmatinska županija",         18),
            Map.entry("Istarska županija",                     19),
            Map.entry("Dubrovačko-neretvanska županija",       20),
            Map.entry("Međimurska županija",                   21),
            Map.entry("Grad Zagreb",                           22)
    );

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
        int countyCode = countyCode(accommodation);
        int typeCode = accommodation.getAccommodationTypeId() != null
                ? accommodation.getAccommodationTypeId().intValue() : 0;

        LocalDate today = LocalDate.now(clock);
        for (int i = 0; i < MAX_RN_ATTEMPTS; i++) {
            String candidate = RegistrationNumber.generate(countyCode, 0, typeCode).getValue();
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
    public RnEntity suspend(String rn, RnTrigger trigger, LocalDate suspensionDeadline, String note) {
        if (trigger != RnTrigger.CONSENT_EXPIRY
                && trigger != RnTrigger.INSPECTION
                && trigger != RnTrigger.INCOMPLETE_DOCUMENTATION
                && trigger != RnTrigger.OTHER) {
            throw new BusinessException("error.rn.suspend.trigger.invalid");
        }
        if (trigger == RnTrigger.OTHER && Strings.blankToNull(note) == null) {
            throw new BusinessException("error.rn.suspend.note.required");
        }
        RnEntity e = load(rn);
        transitionService.transition(e, RnStatus.SUSPENSION_PROPOSED, trigger, null, Strings.blankToNull(note));
        e.setSuspensionDeadline(suspensionDeadline);
        return e;
    }

    @Transactional
    public RnEntity revokeProposal(String rn) {
        RnEntity e = load(rn);
        transitionService.transition(e, RnStatus.ACTIVE, RnTrigger.REVOKE_PROPOSAL);
        return e;
    }

    @Transactional
    public RnEntity reactivate(String rn) {
        RnEntity e = load(rn);
        transitionService.transition(e, RnStatus.ACTIVE, RnTrigger.REACTIVATE);
        return e;
    }

    /**
     * Officer-initiated withdrawal (povlačenje) of a registration number → WITHDRAWN.
     * Permanent: allowed from ACTIVE and SUSPENDED, never reactivated (čl. 6 STR Uredbe).
     * The optional reason is persisted on the audit log. STR-2.1-002.
     *
     * <p>TODO (zasebni epic, "Zajedničke backend teme"): obavijest Internetskim platformama
     * (mail/M2M) + dostava potvrde u KP. Retencija (18 mj.) se računa iz {@code valid_to}
     * koji {@link RnEntity#applyStatus} postavlja na dan povlačenja.
     */
    @Transactional
    public RnEntity withdraw(String rn, String reason) {
        RnEntity e = load(rn);
        transitionService.transition(e, RnStatus.WITHDRAWN, RnTrigger.WITHDRAWAL, null, Strings.blankToNull(reason));
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

    /** STR-1.5: list of inactive RNs (SUSPENSION_PROPOSED + SUSPENDED + WITHDRAWN), for display to competent authority. */
    @Transactional(readOnly = true)
    public List<RnEntity> inactive() {
        return repository.findByStatusInOrderByUpdatedAtDesc(
                List.of(RnStatus.SUSPENSION_PROPOSED, RnStatus.SUSPENDED, RnStatus.WITHDRAWN));
    }

    /** STR wireframe §12 / §13: public registry of active or invalid RNs with filters + paging. */
    @Transactional(readOnly = true)
    public Page<RnSummaryDto> searchRegistry(RnRegistryView view, String q, String county, String municipality,
                                             Long typeId, boolean foreignOnly, String rb, String city,
                                             String street, String name, String lessor, Pageable pageable) {
        String[] t = SearchTokens.slots(q);
        return repository.searchRegistry(view.statuses(),
                t[0], t[1], t[2], t[3], t[4], t[5], t[6], t[7], t[8], t[9],
                Strings.blankToNull(county), Strings.blankToNull(municipality), typeId,
                foreignOnly ? "true" : null,
                Strings.blankToNull(rb), Strings.blankToNull(city), Strings.blankToNull(street),
                Strings.blankToNull(name), Strings.blankToNull(lessor),
                pageable);
    }

    /** STR wireframe §12 / §13: export of registry rows matching filters (max 50 000). */
    @Transactional(readOnly = true)
    public List<RnSummaryDto> searchRegistryForExport(RnRegistryView view, String q, String county,
                                                      String municipality, Long typeId, boolean foreignOnly,
                                                      String rb, String city, String street,
                                                      String name, String lessor) {
        String[] t = SearchTokens.slots(q);
        Page<RnSummaryDto> page = repository.searchRegistry(view.statuses(),
                t[0], t[1], t[2], t[3], t[4], t[5], t[6], t[7], t[8], t[9],
                Strings.blankToNull(county), Strings.blankToNull(municipality), typeId,
                foreignOnly ? "true" : null,
                Strings.blankToNull(rb), Strings.blankToNull(city), Strings.blankToNull(street),
                Strings.blankToNull(name), Strings.blankToNull(lessor),
                PageRequest.of(0, 50_001));
        return page.getContent();
    }

    /** STR wireframe §12 / §13: full detail for a single RN (joined accommodation + lessor). */
    @Transactional(readOnly = true)
    public RnDetailDto detail(String rn) {
        return repository.findDetail(rn)
                .orElseThrow(() -> new ResourceNotFoundException("rn not found: " + rn));
    }

    private static int countyCode(AccommodationEntity accommodation) {
        return COUNTY_EGOP_ORG_IDS.getOrDefault(accommodation.getCounty(), 0);
    }

    private RnEntity load(String rn) {
        return repository.findById(rn)
                .orElseThrow(() -> new ResourceNotFoundException("rn not found: " + rn));
    }
}
