package com.str.backend.request;

import com.str.backend.domain.EgopSyncStatus;
import com.str.backend.domain.SubmissionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubmissionRepository extends JpaRepository<SubmissionEntity, UUID> {

    Optional<SubmissionEntity> findByFilingNumber(String filingNumber);

    boolean existsByFilingNumber(String filingNumber);

    List<SubmissionEntity> findByStatus(SubmissionStatus status);

    List<SubmissionEntity> findByLessorId(UUID lessorId);

    /**
     * Najveći dodijeljeni redni broj predmeta u uredskoj godini — sjeme brojača u
     * {@code LocalFilingNumberAllocator}. Bez njega mock nakon restarta ponavlja KLASU, a KLASA
     * je jedino što nosi jedinstvenost {@code uq_submission_filing_number} otkad je urudžbeni
     * broj redni broj unutar predmeta (ULAZNO je uvijek 1).
     */
    @Transactional(readOnly = true)
    @Query("SELECT COALESCE(MAX(s.egopRbrPredmeta), 0) FROM SubmissionEntity s"
            + " WHERE s.egopUredskaGodina = :godina")
    int maxEgopRbrPredmeta(@Param("godina") int godina);

    /** eGOP retry job: submissioni koji nisu urudžbirani (nedovršen ili neuspio sync),
     *  a nisu iscrpili pokušaje ni stariji od window-a; grace period izbjegava utrku
     *  s in-flight prvim pokušajem, a egopNextAttemptAt provodi eksponencijalni backoff
     *  (NULL = još nije bilo pokušaja, pa vrijedi samo grace). */
    @Transactional(readOnly = true)
    @Query("SELECT s.submissionId FROM SubmissionEntity s"
            + " WHERE s.egopSyncStatus <> :synced"
            + " AND s.egopSyncAttempts < :maxAttempts"
            + " AND s.createdAt < :notAfter"
            + " AND s.createdAt >= :notBefore"
            + " AND (s.egopNextAttemptAt IS NULL OR s.egopNextAttemptAt <= :now)"
            + " ORDER BY s.createdAt")
    List<UUID> findEgopRetryCandidates(@Param("synced") EgopSyncStatus synced,
                                       @Param("maxAttempts") int maxAttempts,
                                       @Param("notAfter") Instant notAfter,
                                       @Param("notBefore") Instant notBefore,
                                       @Param("now") Instant now);
}
