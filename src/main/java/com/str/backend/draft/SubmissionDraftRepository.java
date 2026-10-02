package com.str.backend.draft;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubmissionDraftRepository extends JpaRepository<SubmissionDraftEntity, UUID> {

    @Transactional(readOnly = true)
    List<SubmissionDraftEntity> findByOwnerTypeAndOwnerKeyOrderByUpdatedAtDesc(DraftOwnerType ownerType, String ownerKey);

    @Transactional(readOnly = true)
    List<SubmissionDraftEntity> findByOwnerTypeAndOwnerKeyAndFacilityIdOrderByUpdatedAtDesc(
            DraftOwnerType ownerType, String ownerKey, String facilityId);

    @Transactional(readOnly = true)
    Optional<SubmissionDraftEntity> findFirstByOwnerTypeAndOwnerKeyAndFacilityIdOrderByUpdatedAtDesc(
            DraftOwnerType ownerType, String ownerKey, String facilityId);

    @Transactional(readOnly = true)
    long countByOwnerTypeAndOwnerKey(DraftOwnerType ownerType, String ownerKey);

    @Transactional(readOnly = true)
    Optional<SubmissionDraftEntity> findByDraftIdAndOwnerTypeAndOwnerKey(UUID draftId, DraftOwnerType ownerType, String ownerKey);

    /** Nacrti bez objekta — za {@link DraftFacilityBackfill}. */
    List<SubmissionDraftEntity> findByFacilityIdIsNull();

    long deleteByUpdatedAtBefore(Instant cutoff);

    /**
     * Svi nacrti objekta, bez obzira na vlasnika — poziva se pri izdavanju RB-a za objekt.
     * Bulk delete: ne učitava šifrirane payloade samo da bi ih obrisao.
     */
    @Modifying
    @Query("delete from SubmissionDraftEntity d where d.facilityId = :facilityId")
    int deleteByFacilityId(@Param("facilityId") String facilityId);
}
