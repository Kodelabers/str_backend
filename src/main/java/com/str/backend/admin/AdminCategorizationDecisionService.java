package com.str.backend.admin;

import com.str.backend.categorization.CategorizationDecisionAdminDto;
import com.str.backend.categorization.CategorizationDecisionEntity;
import com.str.backend.categorization.CategorizationDecisionRepository;
import com.str.backend.categorization.CategorizationDecisionStatus;
import com.str.backend.categorization.CategorizationFileDto;
import com.str.backend.exception.ResourceNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Interni pregled skeniranih rješenja o kategorizaciji za nadležno tijelo. Radi isključivo nad
 * {@code str_rn.categorization_decision} (naša tablica) — pregled, download skena, te prihvat
 * (verify) i odbijanje (reject). Audit se piše kroz {@link AdminAuditService}, kao i za ostale
 * admin akcije.
 *
 * <p>Obrazac je namjerno isti kao {@link AdminPendingRegistrationService}: actor je za sada
 * caller-supplied (v. {@code TODO(auth)} u kontroleru) dok NIAS role ne daju pouzdan identitet.
 */
@Service
public class AdminCategorizationDecisionService {

    private final CategorizationDecisionRepository repository;
    private final AdminAuditService auditService;

    public AdminCategorizationDecisionService(CategorizationDecisionRepository repository,
                                              AdminAuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public Page<CategorizationDecisionAdminDto> list(CategorizationDecisionStatus status, Pageable pageable) {
        Page<CategorizationDecisionEntity> page = status != null
                ? repository.findByStatus(status, pageable)
                : repository.findAll(pageable);
        return page.map(CategorizationDecisionAdminDto::of);
    }

    @Transactional(readOnly = true)
    public CategorizationDecisionAdminDto detail(UUID id) {
        return CategorizationDecisionAdminDto.of(findOrThrow(id));
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
