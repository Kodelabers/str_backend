package com.str.backend.admin;

import com.str.backend.auth.internal.InternalAccessService;
import com.str.backend.categorization.CategorizationDecisionAdminDto;
import com.str.backend.categorization.CategorizationDecisionEntity;
import com.str.backend.categorization.CategorizationDecisionRepository;
import com.str.backend.categorization.CategorizationDecisionStatus;
import com.str.backend.categorization.CategorizationFileDto;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Interni pregled skeniranih rješenja o kategorizaciji za nadležno tijelo. Radi isključivo nad
 * {@code str_rn.categorization_decision} (naša tablica) — pregled, download skena, te prihvat
 * (verify) i odbijanje (reject). Audit se piše kroz {@link AdminAuditService}, kao i za ostale
 * admin akcije.
 *
 * <p>Obrazac je namjerno isti kao {@link AdminPendingRegistrationService}: akter je službenik
 * iz NIAS sesije ({@code INTERNAL:<oib>}, v. {@code InternalAccessService#actorFor}), a u
 * sučelju se prikazuje njegovo ime ({@code InternalAccessService#displayNames}).
 */
@Service
public class AdminCategorizationDecisionService {

    private final CategorizationDecisionRepository repository;
    private final AdminAuditService auditService;
    private final InternalAccessService internalAccess;
    private final LessorRepository lessorRepository;

    public AdminCategorizationDecisionService(CategorizationDecisionRepository repository,
                                              AdminAuditService auditService,
                                              InternalAccessService internalAccess,
                                              LessorRepository lessorRepository) {
        this.repository = repository;
        this.auditService = auditService;
        this.internalAccess = internalAccess;
        this.lessorRepository = lessorRepository;
    }

    /**
     * Interni red. Zapis koji je pri ponovnom izdavanju RB-a prenesen na novi RB (T15) ovdje se ne
     * prikazuje — obrađuje se kopija uz novi RB, inače bi isti sken bio u redu dvaput.
     */
    @Transactional(readOnly = true)
    public Page<CategorizationDecisionAdminDto> list(CategorizationDecisionStatus status, Pageable pageable) {
        Page<CategorizationDecisionEntity> page = status != null
                ? repository.findForReviewByStatus(status, pageable)
                : repository.findForReview(pageable);
        // Jedan upit za imena svih službenika na stranici, ne po retku; isto za porezne brojeve.
        Map<String, String> names = internalAccess.displayNames(
                page.getContent().stream().map(CategorizationDecisionEntity::getVerifiedBy).toList());
        Map<UUID, String> taxNumbers = taxNumbers(page.getContent());
        return page.map(e -> CategorizationDecisionAdminDto.of(e, names.get(e.getVerifiedBy()),
                taxNumbers.get(e.getLessorId())));
    }

    @Transactional(readOnly = true)
    public CategorizationDecisionAdminDto detail(UUID id) {
        CategorizationDecisionEntity e = findOrThrow(id);
        Map<String, String> names = internalAccess.displayNames(
                e.getVerifiedBy() == null ? List.of() : List.of(e.getVerifiedBy()));
        return CategorizationDecisionAdminDto.of(e, names.get(e.getVerifiedBy()),
                taxNumbers(List.of(e)).get(e.getLessorId()));
    }

    /**
     * Strani porezni broj non-EU iznajmljivača po {@code lessorId} — on nema OIB, pa se u stupcu
     * „OIB iznajmljivača" prikazuje porezni broj. NIAS zapisi nemaju {@code lessorId}.
     */
    private Map<UUID, String> taxNumbers(List<CategorizationDecisionEntity> decisions) {
        Set<UUID> ids = decisions.stream()
                .map(CategorizationDecisionEntity::getLessorId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        // Ne Map.of(): NIAS zapisi traže po null ključu, a nepromjenjiva mapa tada baca NPE.
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return lessorRepository.findAllById(ids).stream()
                .filter(l -> l.getTaxNumber() != null)
                .collect(Collectors.toMap(LessorEntity::getLessorId, LessorEntity::getTaxNumber));
    }

    @Transactional(readOnly = true)
    public CategorizationFileDto file(UUID id) {
        CategorizationDecisionEntity e = findOrThrow(id);
        return new CategorizationFileDto(e.getFileName(), e.getContentType(), e.getFileContent());
    }

    @Transactional
    public void verify(UUID id, String actor) {
        CategorizationDecisionEntity e = findOrThrow(id);
        e.verify(actor);
        auditService.record(actor, "CATEGORIZATION_VERIFY", "CATEGORIZATION_DECISION", id.toString(), null);
        // TODO(eTurizam): kad MINTS/Simon potvrde tko upisuje objekt u eTurizam registar, ovdje
        //   ide upis objekta + dodjela facility_id (e.assignFacility(...)). Do tada je VERIFIED
        //   čista oznaka — str.* nam je read-only, pa upis vjerojatno radi eTurizam servis.
    }

    @Transactional
    public void reject(UUID id, String actor, String reason) {
        CategorizationDecisionEntity e = findOrThrow(id);
        e.reject(actor);
        auditService.record(actor, "CATEGORIZATION_REJECT", "CATEGORIZATION_DECISION", id.toString(), reason);
    }

    private CategorizationDecisionEntity findOrThrow(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Rješenje o kategorizaciji nije pronađeno: " + id));
    }
}
