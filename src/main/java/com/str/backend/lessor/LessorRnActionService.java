package com.str.backend.lessor;

import com.str.backend.common.Strings;
import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.request.SubmissionRepository;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnRepository;
import com.str.backend.rn.RnStatusTransitionService;
import com.str.backend.rn.dto.RnDetailDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Lessor-facing RN actions on the external portal — currently self-revocation (opoziv).
 * The action is authorized against ownership: the RN must belong, through its submission,
 * to the authenticated lessor. A non-owned (or unknown) RN is reported as
 * {@link ResourceNotFoundException} so the endpoint does not leak existence to other lessors.
 */
@Service
public class LessorRnActionService {

    private static final Logger log = LoggerFactory.getLogger(LessorRnActionService.class);

    private final RnRepository rnRepository;
    private final SubmissionRepository submissionRepository;
    private final LessorRepository lessorRepository;
    private final RnStatusTransitionService transitionService;

    public LessorRnActionService(RnRepository rnRepository,
                                 SubmissionRepository submissionRepository,
                                 LessorRepository lessorRepository,
                                 RnStatusTransitionService transitionService) {
        this.rnRepository = rnRepository;
        this.submissionRepository = submissionRepository;
        this.lessorRepository = lessorRepository;
        this.transitionService = transitionService;
    }

    /**
     * Lessor-initiated revocation (opoziv) of their own RN → WITHDRAWN ("Brisan").
     * Permanent, allowed from ACTIVE or SUSPENDED (čl. 5. st. 5. STR Uredbe). STR-1.3-001.
     *
     * <p>TODO (zasebni epic, "Zajedničke backend teme"): potvrda u KP + obavijest Internetskim
     * platformama. 18-mj. retencijski/brisaći brojač računa se iz {@code valid_to} koji
     * {@link RnEntity#applyStatus} postavlja na dan opoziva.
     */
    @Transactional
    public LessorRnActionResponse withdrawOwn(String rn, UUID lessorId, String reason) {
        RnEntity entity = loadOwnedByLessorId(rn, lessorId);
        transitionService.transition(entity, RnStatus.WITHDRAWN, RnTrigger.WITHDRAWAL,
                "LESSOR:" + lessorId, Strings.blankToNull(reason));
        log.info("rn_withdraw_by_lessor rn={} lessor={}", rn, lessorId);
        return new LessorRnActionResponse(entity.getRn(), entity.getStatus());
    }

    /**
     * NIAS varijanta: vlasništvo se provjerava po OIB-u jer svaki NIAS submission
     * kreira novi LessorEntity snapshot — fiksan lessorId ne pokriva starije RB-ove
     * istog korisnika. Provjera: submission.lessorId → LessorEntity.lessorOib == oib.
     */
    @Transactional
    public LessorRnActionResponse withdrawOwnByOib(String rn, String oib, String reason) {
        RnEntity entity = loadOwnedByOib(rn, oib);
        transitionService.transition(entity, RnStatus.WITHDRAWN, RnTrigger.WITHDRAWAL,
                "NIAS:" + oib, Strings.blankToNull(reason));
        log.info("rn_withdraw_by_nias rn={} oib={}", rn, oib);
        return new LessorRnActionResponse(entity.getRn(), entity.getStatus());
    }

    /**
     * Owner-scoped detalj RB-a za LOCAL (ne-EU) korisnika: vraća pun {@link RnDetailDto} samo ako
     * RB pripada tom {@code lessorId}; inače 404 (bez otkrivanja postojanja tuđeg RB-a). USER preko
     * ove rute vidi isključivo svoje RB-ove; INTERNAL koristi javni registar ({@code /api/rn}).
     */
    @Transactional(readOnly = true)
    public RnDetailDto detailOwnedByLessorId(String rn, UUID lessorId) {
        loadOwnedByLessorId(rn, lessorId);
        return rnRepository.findDetail(rn)
                .orElseThrow(() -> new ResourceNotFoundException("rn not found: " + rn));
    }

    /** NIAS varijanta owner-scoped detalja: vlasništvo po OIB-u (RB → submission → lessor.lessorOib). */
    @Transactional(readOnly = true)
    public RnDetailDto detailOwnedByOib(String rn, String oib) {
        loadOwnedByOib(rn, oib);
        return rnRepository.findDetail(rn)
                .orElseThrow(() -> new ResourceNotFoundException("rn not found: " + rn));
    }

    /** Loads the RN and verifies it belongs to the lessor; 404 otherwise (no existence leak). */
    private RnEntity loadOwnedByLessorId(String rn, UUID lessorId) {
        RnEntity entity = rnRepository.findById(rn)
                .orElseThrow(() -> new ResourceNotFoundException("rn not found: " + rn));
        UUID submissionId = entity.getSubmissionId();
        boolean owned = submissionId != null
                && submissionRepository.findById(submissionId)
                        .map(s -> lessorId.equals(s.getLessorId()))
                        .orElse(false);
        if (!owned) {
            throw new ResourceNotFoundException("rn not found: " + rn);
        }
        return entity;
    }

    /** OIB-based ownership: RN → submission → LessorEntity.lessorOib must match. */
    private RnEntity loadOwnedByOib(String rn, String oib) {
        RnEntity entity = rnRepository.findById(rn)
                .orElseThrow(() -> new ResourceNotFoundException("rn not found: " + rn));
        UUID submissionId = entity.getSubmissionId();
        boolean owned = submissionId != null
                && submissionRepository.findById(submissionId)
                        .map(SubmissionEntity::getLessorId)
                        .flatMap(lessorRepository::findById)
                        .map(l -> oib.equals(l.getLessorOib()))
                        .orElse(false);
        if (!owned) {
            throw new ResourceNotFoundException("rn not found: " + rn);
        }
        return entity;
    }
}
