package com.str.backend.admin;

import com.str.backend.auth.internal.InternalAccessService;
import com.str.backend.categorization.CategorizationDecisionAdminDto;
import com.str.backend.categorization.CategorizationDecisionEntity;
import com.str.backend.categorization.CategorizationDecisionRepository;
import com.str.backend.categorization.CategorizationDecisionStatus;
import com.str.backend.categorization.CategorizationFileDto;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.auth.internal.CountyScope;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.rn.RnRepository;
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
    private final RnRepository rnRepository;

    public AdminCategorizationDecisionService(CategorizationDecisionRepository repository,
                                              AdminAuditService auditService,
                                              InternalAccessService internalAccess,
                                              LessorRepository lessorRepository,
                                              RnRepository rnRepository) {
        this.repository = repository;
        this.auditService = auditService;
        this.internalAccess = internalAccess;
        this.lessorRepository = lessorRepository;
        this.rnRepository = rnRepository;
    }

    /**
     * Interni red. Zapis koji je pri ponovnom izdavanju RB-a prenesen na novi RB (T15) ovdje se ne
     * prikazuje — obrađuje se kopija uz novi RB, inače bi isti sken bio u redu dvaput.
     *
     * @param scopeCounties opseg službenika ({@code accommodation.county} RB-a); {@code null} = cijela RH
     */
    @Transactional(readOnly = true)
    public Page<CategorizationDecisionAdminDto> list(CategorizationDecisionStatus status, List<String> scopeCounties,
                                                     Pageable pageable) {
        Page<CategorizationDecisionEntity> page;
        if (scopeCounties == null) {
            page = status != null
                    ? repository.findForReviewByStatus(status, pageable)
                    : repository.findForReview(pageable);
        } else {
            page = status != null
                    ? repository.findForReviewByStatusInCounties(status, scopeCounties, pageable)
                    : repository.findForReviewInCounties(scopeCounties, pageable);
        }
        // Jedan upit za imena svih službenika na stranici, ne po retku; isto za porezne brojeve.
        Map<String, String> names = internalAccess.displayNames(
                page.getContent().stream().map(CategorizationDecisionEntity::getVerifiedBy).toList());
        Map<UUID, String> taxNumbers = taxNumbers(page.getContent());
        return page.map(e -> CategorizationDecisionAdminDto.of(e, names.get(e.getVerifiedBy()),
                taxNumbers.get(e.getLessorId())));
    }

    @Transactional(readOnly = true)
    public CategorizationDecisionAdminDto detail(UUID id, CountyScope scope) {
        CategorizationDecisionEntity e = findOrThrow(id, scope);
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
    public CategorizationFileDto file(UUID id, CountyScope scope) {
        CategorizationDecisionEntity e = findOrThrow(id, scope);
        return new CategorizationFileDto(e.getFileName(), e.getContentType(), e.getFileContent());
    }

    @Transactional
    public void verify(UUID id, CountyScope scope, String actor) {
        CategorizationDecisionEntity e = findOrThrow(id, scope);
        e.verify(actor);
        auditService.record(actor, "CATEGORIZATION_VERIFY", "CATEGORIZATION_DECISION", id.toString(), null);
        // TODO(eTurizam): kad MINTS/Simon potvrde tko upisuje objekt u eTurizam registar, ovdje
        //   ide upis objekta + dodjela facility_id (e.assignFacility(...)). Do tada je VERIFIED
        //   čista oznaka — str.* nam je read-only, pa upis vjerojatno radi eTurizam servis.
    }

    @Transactional
    public void reject(UUID id, CountyScope scope, String actor, String reason) {
        CategorizationDecisionEntity e = findOrThrow(id, scope);
        e.reject(actor);
        auditService.record(actor, "CATEGORIZATION_REJECT", "CATEGORIZATION_DECISION", id.toString(), reason);
    }

    /**
     * Rješenje izvan opsega službenika (RB u drugoj županiji ili bez RB-a) je nepostojeće (404),
     * kao u redu, iz kojeg ga ne vidi.
     */
    private CategorizationDecisionEntity findOrThrow(UUID id, CountyScope scope) {
        return repository.findById(id)
                .filter(e -> !scope.restricted()
                        || (e.getRn() != null && scope.covers(rnRepository.findCountyByRn(e.getRn()).orElse(null))))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Rješenje o kategorizaciji nije pronađeno: " + id));
    }
}
