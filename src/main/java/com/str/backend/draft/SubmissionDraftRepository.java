package com.str.backend.draft;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubmissionDraftRepository extends JpaRepository<SubmissionDraftEntity, UUID> {

    @Transactional(readOnly = true)
    List<SubmissionDraftEntity> findByOwnerTypeAndOwnerKeyOrderByUpdatedAtDesc(DraftOwnerType ownerType, String ownerKey);

    @Transactional(readOnly = true)
    long countByOwnerTypeAndOwnerKey(DraftOwnerType ownerType, String ownerKey);

    @Transactional(readOnly = true)
    Optional<SubmissionDraftEntity> findByDraftIdAndOwnerTypeAndOwnerKey(UUID draftId, DraftOwnerType ownerType, String ownerKey);

    long deleteByUpdatedAtBefore(Instant cutoff);
}
