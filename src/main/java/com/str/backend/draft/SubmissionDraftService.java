package com.str.backend.draft;

import com.str.backend.draft.dto.DraftListItemResponse;
import com.str.backend.draft.dto.DraftRequest;
import com.str.backend.draft.dto.DraftResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Nacrti zahtjeva za RB. Nacrt postojećeg eTurizam objekta nosi {@code facilityId}: vlasnik ima
 * najviše jedan nacrt po objektu (spremanje novog ažurira postojeći), a izdavanje RB-a za objekt
 * briše sve njegove nacrte ({@link #discardForFacility}). Nacrti novog objekta nemaju objekt i
 * ponašaju se kao prije — svako spremanje bez {@code draftId} je novi nacrt.
 */
@Service
public class SubmissionDraftService {

    private final SubmissionDraftRepository repository;
    private final DraftEncryptionService encryption;
    private final int maxPerOwner;

    public SubmissionDraftService(SubmissionDraftRepository repository,
                                  DraftEncryptionService encryption,
                                  @Value("${app.draft.max-per-owner:10}") int maxPerOwner) {
        this.repository = repository;
        this.encryption = encryption;
        this.maxPerOwner = maxPerOwner;
    }

    /** Ishod spremanja: {@code created=false} kad je upsert ažurirao postojeći nacrt objekta. */
    public record SaveResult(DraftListItemResponse draft, boolean created) {
    }

    /**
     * @param facilityId kad je zadan, samo nacrti tog objekta (najnoviji prvi) — po tome forma
     *                   pri otvaranju objekta učita njegov nacrt
     */
    @Transactional(readOnly = true)
    public List<DraftListItemResponse> list(DraftOwner owner, String facilityId) {
        List<SubmissionDraftEntity> drafts = isBlank(facilityId)
                ? repository.findByOwnerTypeAndOwnerKeyOrderByUpdatedAtDesc(owner.type(), owner.key())
                : repository.findByOwnerTypeAndOwnerKeyAndFacilityIdOrderByUpdatedAtDesc(
                        owner.type(), owner.key(), facilityId.trim());
        return drafts.stream().map(SubmissionDraftService::toListItem).toList();
    }

    @Transactional(readOnly = true)
    public DraftResponse get(UUID draftId, DraftOwner owner) {
        SubmissionDraftEntity e = findOwned(draftId, owner);
        return new DraftResponse(e.getDraftId(), e.getTitle(), e.getOwnerType(), e.getFacilityId(),
                encryption.decrypt(e.getPayload()), e.getCreatedAt(), e.getUpdatedAt());
    }

    /**
     * Novi nacrt — osim kad vlasnik za isti objekt već ima nacrt: tada se ažurira taj (jedan
     * nacrt po objektu, npr. spremanje iz drugog prozora koji nacrt nije učitao). Upsert se ne
     * broji u limit, jer ne dodaje nacrt.
     */
    @Transactional
    public SaveResult create(DraftOwner owner, DraftRequest request) {
        String facilityId = request.normalizedFacilityId();
        Optional<SubmissionDraftEntity> existing = facilityId == null
                ? Optional.empty()
                : repository.findFirstByOwnerTypeAndOwnerKeyAndFacilityIdOrderByUpdatedAtDesc(
                        owner.type(), owner.key(), facilityId);
        if (existing.isPresent()) {
            return new SaveResult(save(existing.get(), request), false);
        }
        long count = repository.countByOwnerTypeAndOwnerKey(owner.type(), owner.key());
        if (count >= maxPerOwner) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Dosegnut je maksimum od " + maxPerOwner + " nacrta. Obriši stari nacrt prije spremanja novog.");
        }
        SubmissionDraftEntity entity = SubmissionDraftEntity.create(owner, request.title(),
                encryption.encrypt(request.payload()), facilityId);
        return new SaveResult(toListItem(repository.save(entity)), true);
    }

    @Transactional
    public DraftListItemResponse update(UUID draftId, DraftOwner owner, DraftRequest request) {
        return save(findOwned(draftId, owner), request);
    }

    @Transactional
    public void delete(UUID draftId, DraftOwner owner) {
        repository.delete(findOwned(draftId, owner));
    }

    /**
     * Briše sve nacrte objekta, svih vlasnika. Zove se u transakciji izdavanja RB-a: RB za objekt
     * dobiva samo njegov vlasnik ({@code FacilityClaimVerifier}), pa ovo ne može obrisati nacrt
     * koji bi itko još mogao predati, a pokriva i duplikate i nacrt spremljen pod drugim vlasnikom.
     */
    @Transactional
    public int discardForFacility(String facilityId) {
        if (isBlank(facilityId)) {
            return 0;
        }
        return repository.deleteByFacilityId(facilityId.trim());
    }

    private DraftListItemResponse save(SubmissionDraftEntity e, DraftRequest request) {
        e.update(request.title(), encryption.encrypt(request.payload()), request.normalizedFacilityId());
        return toListItem(repository.save(e));
    }

    private SubmissionDraftEntity findOwned(UUID draftId, DraftOwner owner) {
        return repository.findByDraftIdAndOwnerTypeAndOwnerKey(draftId, owner.type(), owner.key())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nacrt nije pronađen."));
    }

    private static DraftListItemResponse toListItem(SubmissionDraftEntity e) {
        return new DraftListItemResponse(e.getDraftId(), e.getTitle(), e.getOwnerType(), e.getFacilityId(),
                e.getCreatedAt(), e.getUpdatedAt());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
