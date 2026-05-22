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
import java.util.UUID;

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

    @Transactional(readOnly = true)
    public List<DraftListItemResponse> list(DraftOwner owner) {
        return repository.findByOwnerTypeAndOwnerKeyOrderByUpdatedAtDesc(owner.type(), owner.key())
                .stream()
                .map(e -> new DraftListItemResponse(e.getDraftId(), e.getTitle(), e.getOwnerType(), e.getCreatedAt(), e.getUpdatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public DraftResponse get(UUID draftId, DraftOwner owner) {
        SubmissionDraftEntity e = repository.findByDraftIdAndOwnerTypeAndOwnerKey(draftId, owner.type(), owner.key())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nacrt nije pronađen."));
        return new DraftResponse(e.getDraftId(), e.getTitle(), e.getOwnerType(), encryption.decrypt(e.getPayload()), e.getCreatedAt(), e.getUpdatedAt());
    }

    @Transactional
    public DraftListItemResponse create(DraftOwner owner, DraftRequest request) {
        long count = repository.countByOwnerTypeAndOwnerKey(owner.type(), owner.key());
        if (count >= maxPerOwner) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Dosegnut je maksimum od " + maxPerOwner + " nacrta. Obriši stari nacrt prije spremanja novog.");
        }
        SubmissionDraftEntity entity = SubmissionDraftEntity.create(owner, request.title(), encryption.encrypt(request.payload()));
        SubmissionDraftEntity saved = repository.save(entity);
        return new DraftListItemResponse(saved.getDraftId(), saved.getTitle(), saved.getOwnerType(), saved.getCreatedAt(), saved.getUpdatedAt());
    }

    @Transactional
    public DraftListItemResponse update(UUID draftId, DraftOwner owner, DraftRequest request) {
        SubmissionDraftEntity e = repository.findByDraftIdAndOwnerTypeAndOwnerKey(draftId, owner.type(), owner.key())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nacrt nije pronađen."));
        e.update(request.title(), encryption.encrypt(request.payload()));
        SubmissionDraftEntity saved = repository.save(e);
        return new DraftListItemResponse(saved.getDraftId(), saved.getTitle(), saved.getOwnerType(), saved.getCreatedAt(), saved.getUpdatedAt());
    }

    @Transactional
    public void delete(UUID draftId, DraftOwner owner) {
        SubmissionDraftEntity e = repository.findByDraftIdAndOwnerTypeAndOwnerKey(draftId, owner.type(), owner.key())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nacrt nije pronađen."));
        repository.delete(e);
    }
}
