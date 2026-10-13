package com.str.backend.categorization;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CategorizationDecisionRepository extends JpaRepository<CategorizationDecisionEntity, UUID> {

    /** Interni pregled za nadležno tijelo — filtar po statusu (SUBMITTED/VERIFIED/REJECTED). */
    @Transactional(readOnly = true)
    Page<CategorizationDecisionEntity> findByStatus(CategorizationDecisionStatus status, Pageable pageable);

    /**
     * Uvjet internog reda: zapis koji je pri ponovnom izdavanju (T15) prenesen na novi RB ne ide u
     * red — obrađuje se kopija ({@code source_decision_id} = id ovog zapisa), a izvor ostaje samo
     * kao povijest povučenog RB-a.
     */
    String NOT_CARRIED_OVER = " NOT EXISTS (SELECT 1 FROM CategorizationDecisionEntity c"
            + " WHERE c.sourceDecisionId = d.decisionId)";

    /** Interni red za nadležno tijelo, bez prenesenih izvora — v. {@link #NOT_CARRIED_OVER}. */
    @Transactional(readOnly = true)
    @Query(value = "SELECT d FROM CategorizationDecisionEntity d WHERE" + NOT_CARRIED_OVER,
            countQuery = "SELECT COUNT(d) FROM CategorizationDecisionEntity d WHERE" + NOT_CARRIED_OVER)
    Page<CategorizationDecisionEntity> findForReview(Pageable pageable);

    /** Kao {@link #findForReview}, uz filtar po statusu. */
    @Transactional(readOnly = true)
    @Query(value = "SELECT d FROM CategorizationDecisionEntity d WHERE d.status = :status AND" + NOT_CARRIED_OVER,
            countQuery = "SELECT COUNT(d) FROM CategorizationDecisionEntity d WHERE d.status = :status AND"
                    + NOT_CARRIED_OVER)
    Page<CategorizationDecisionEntity> findForReviewByStatus(@Param("status") CategorizationDecisionStatus status,
                                                             Pageable pageable);

    /** Sažetak rješenja bez sadržaja datoteke (predpopuna ponovnog zahtjeva ne treba BLOB). */
    interface DecisionSummary {
        UUID getDecisionId();
        String getFileName();
        CategorizationDecisionStatus getStatus();
        String getFacilityId();
    }

    /**
     * Sažeci rješenja uz RB u zadanim statusima, najnovije prvo — bez {@code file_content}. Aktivno
     * (predano/potvrđeno) je najviše jedno po RB-u (changeset 126).
     */
    @Transactional(readOnly = true)
    @Query("""
            SELECT d.decisionId AS decisionId, d.fileName AS fileName, d.status AS status,
                   d.facilityId AS facilityId
            FROM CategorizationDecisionEntity d
            WHERE d.rn = :rn AND d.status IN :statuses
            ORDER BY d.uploadedAt DESC
            """)
    List<DecisionSummary> findSummariesByRnAndStatusIn(@Param("rn") String rn,
                                                       @Param("statuses") Collection<CategorizationDecisionStatus> statuses);

    /**
     * Zapisi koje na korisnikovom popisu treba prikazati kao privremeno rješenje: stari
     * samostalni uploadi (bez RB-a) koji još nisu odbijeni i još nemaju objekt u eTurizmu.
     * Kad {@code facilityId} bude popunjen, objekt dolazi iz {@code str.facility} i ovaj zapis
     * se više ne prikazuje zasebno. Rješenje vezano uz RB ovdje ne ide — objekt tog RB-a
     * korisnik već ima u svojim registracijskim brojevima.
     */
    @Transactional(readOnly = true)
    List<CategorizationDecisionEntity> findByLessorOibAndFacilityIdIsNullAndRnIsNullAndStatusNotOrderByUploadedAtDesc(
            String lessorOib, CategorizationDecisionStatus status);

    @Transactional(readOnly = true)
    List<CategorizationDecisionEntity> findByLessorOibOrderByUploadedAtDesc(String lessorOib);

    /** Postoji li za RB rješenje koje ga „zauzima" (predano ili potvrđeno). */
    @Transactional(readOnly = true)
    boolean existsByRnAndStatusIn(String rn, Collection<CategorizationDecisionStatus> statuses);

    /**
     * Rješenje koje „zauzima" RB (predano ili potvrđeno). Najviše jedno po RB-u — parcijalni
     * unique index iz changeseta 126 — pa je {@code findFirst} samo zaštita od starih podataka.
     */
    @Transactional(readOnly = true)
    Optional<CategorizationDecisionEntity> findFirstByRnAndStatusInOrderByUploadedAtDesc(
            String rn, Collection<CategorizationDecisionStatus> statuses);
}
