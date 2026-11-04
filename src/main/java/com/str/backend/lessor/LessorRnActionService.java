package com.str.backend.lessor;

import com.str.backend.attachment.PrilogService;
import com.str.backend.attachment.PrilogVlasnik;
import com.str.backend.attachment.Ucitao;
import com.str.backend.common.Strings;
import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.request.SubmissionRepository;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnRepository;
import com.str.backend.rn.RnStatusTransitionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
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
    private final PrilogService prilogService;

    public LessorRnActionService(RnRepository rnRepository,
                                 SubmissionRepository submissionRepository,
                                 LessorRepository lessorRepository,
                                 RnStatusTransitionService transitionService,
                                 PrilogService prilogService) {
        this.rnRepository = rnRepository;
        this.submissionRepository = submissionRepository;
        this.lessorRepository = lessorRepository;
        this.transitionService = transitionService;
        this.prilogService = prilogService;
    }

    /**
     * Lessor-initiated revocation (opoziv) of their own RN → REVOKED ("Opozvan").
     * Final, allowed from ACTIVE, SUSPENSION_PROPOSED or SUSPENDED (čl. 5. st. 5. STR Uredbe).
     * STR-1.3-001. The officer's withdrawal (povlačenje) is a separate final status,
     * {@code WITHDRAWN} — see {@code RnService#withdraw}.
     *
     * <p>Prilozi stranke ({@code prilogIds} iz {@code /api/lessor/attachments}, T17; neobavezni) vežu
     * se uz novi zapis prijelaza u istoj transakciji; kao prilozi stranke idu uz ulazno pismeno
     * „Zahtjev za opoziv", ne uz obavijest o opozivu ({@code PrilogService#pripadaPismenu}).
     *
     * <p>Obavijest o opozivu je izlazni akt (T15) i ide u korisnički pretinac kroz eGOP tok.
     * TODO (zasebni epic, "Zajedničke backend teme"): obavijest Internetskim platformama.
     * 18-mj. retencijski/brisaći brojač računa se iz {@code valid_to} koji
     * {@link RnEntity#applyStatus} postavlja na dan opoziva.
     */
    @Transactional
    public LessorRnActionResponse withdrawOwn(String rn, UUID lessorId, String reason, Collection<Long> prilogIds) {
        RnEntity entity = loadOwnedByLessorId(rn, lessorId);
        String ucitao = Ucitao.lessor(lessorId);
        UUID logId = transitionService.transition(entity, RnStatus.REVOKED, RnTrigger.REVOCATION,
                ucitao, Strings.blankToNull(reason));
        bindPrilozi(ucitao, prilogIds, logId);
        log.info("rn_withdraw_by_lessor rn={} lessor={}", rn, lessorId);
        return new LessorRnActionResponse(entity.getRn(), entity.getStatus());
    }

    /**
     * NIAS varijanta: vlasništvo se provjerava po OIB-u jer svaki NIAS submission
     * kreira novi LessorEntity snapshot — fiksan lessorId ne pokriva starije RB-ove
     * istog korisnika. Provjera: submission.lessorId → LessorEntity.lessorOib == oib. {@code oib} je
     * vlasnik (efektivni OIB), isti kao kod staginga priloga.
     */
    @Transactional
    public LessorRnActionResponse withdrawOwnByOib(String rn, String oib, String reason, Collection<Long> prilogIds) {
        RnEntity entity = loadOwnedByOib(rn, oib);
        String ucitao = Ucitao.nias(oib);
        UUID logId = transitionService.transition(entity, RnStatus.REVOKED, RnTrigger.REVOCATION,
                ucitao, Strings.blankToNull(reason));
        bindPrilozi(ucitao, prilogIds, logId);
        log.info("rn_withdraw_by_nias rn={} oib={}", rn, oib);
        return new LessorRnActionResponse(entity.getRn(), entity.getStatus());
    }

    private void bindPrilozi(String ucitao, Collection<Long> prilogIds, UUID logId) {
        if (prilogIds != null && !prilogIds.isEmpty()) {
            prilogService.bind(ucitao, prilogIds, PrilogVlasnik.prijelaz(logId));
        }
    }

    /**
     * Loads the RN locked for the status change ({@link RnRepository#findForUpdate}) and verifies
     * it belongs to the lessor; 404 otherwise (no existence leak).
     */
    private RnEntity loadOwnedByLessorId(String rn, UUID lessorId) {
        RnEntity entity = rnRepository.findForUpdate(rn)
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

    /** OIB-based ownership: RN → submission → LessorEntity.lessorOib must match. Locked as above. */
    private RnEntity loadOwnedByOib(String rn, String oib) {
        RnEntity entity = rnRepository.findForUpdate(rn)
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
