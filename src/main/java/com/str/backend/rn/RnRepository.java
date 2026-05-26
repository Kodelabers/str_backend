package com.str.backend.rn;

import com.str.backend.domain.RnStatus;
import com.str.backend.rn.dto.RnDetailDto;
import com.str.backend.rn.dto.RnSummaryDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RnRepository extends JpaRepository<RnEntity, String> {

    boolean existsByRn(String rn);

    List<RnEntity> findByAccommodationId(UUID accommodationId);

    List<RnEntity> findBySubmissionId(UUID submissionId);

    Optional<RnEntity> findTopByAccommodationIdAndStatusOrderByCreatedAtDesc(UUID accommodationId, RnStatus status);

    List<RnEntity> findByStatusInOrderByUpdatedAtDesc(List<RnStatus> statuses);

    /** BPSO statistics: counts of RNs grouped by accommodation county + RN status. */
    @Transactional(readOnly = true)
    @Query("""
            SELECT a.county AS county, r.status AS status, COUNT(r) AS count
            FROM RnEntity r JOIN AccommodationEntity a ON a.accommodationId = r.accommodationId
            GROUP BY a.county, r.status
            """)
    List<CountyStatusCount> countByCountyAndStatus();

    interface CountyStatusCount {
        String getCounty();
        RnStatus getStatus();
        long getCount();
    }

    @Transactional(readOnly = true)
    @Query(value = """
            SELECT new com.str.backend.rn.dto.RnSummaryDto(
                r.rn, r.status, r.issueDate, r.validFrom, r.validTo,
                a.accommodationId, a.county, a.city, a.street, a.streetNumber,
                a.name, t.name,
                l.lessorId, l.firstName, l.lastName, l.legalEntityName
            )
            FROM RnEntity r
            JOIN AccommodationEntity a ON a.accommodationId = r.accommodationId
            LEFT JOIN AccommodationTypeEntity t ON t.typeId = a.accommodationTypeId
            LEFT JOIN LessorEntity l ON l.lessorId =
                (SELECT s.lessorId FROM SubmissionEntity s WHERE s.submissionId = r.submissionId)
            WHERE r.status IN :statuses
              AND (CAST(:q AS string) IS NULL OR
                   LOWER(r.rn)              LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')) OR
                   LOWER(a.city)            LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')) OR
                   LOWER(a.street)          LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')) OR
                   LOWER(COALESCE(a.name, '')) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')) OR
                   LOWER(COALESCE(l.firstName, '')) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')) OR
                   LOWER(COALESCE(l.lastName, '')) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')) OR
                   LOWER(COALESCE(l.legalEntityName, '')) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
              AND (CAST(:county AS string) IS NULL OR LOWER(a.county) = LOWER(CAST(:county AS string)))
              AND (:typeId IS NULL OR a.accommodationTypeId = :typeId)
            """,
            countQuery = """
            SELECT COUNT(r) FROM RnEntity r
            JOIN AccommodationEntity a ON a.accommodationId = r.accommodationId
            LEFT JOIN AccommodationTypeEntity t ON t.typeId = a.accommodationTypeId
            LEFT JOIN LessorEntity l ON l.lessorId =
                (SELECT s.lessorId FROM SubmissionEntity s WHERE s.submissionId = r.submissionId)
            WHERE r.status IN :statuses
              AND (CAST(:q AS string) IS NULL OR
                   LOWER(r.rn)              LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')) OR
                   LOWER(a.city)            LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')) OR
                   LOWER(a.street)          LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')) OR
                   LOWER(COALESCE(a.name, '')) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')) OR
                   LOWER(COALESCE(l.firstName, '')) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')) OR
                   LOWER(COALESCE(l.lastName, '')) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')) OR
                   LOWER(COALESCE(l.legalEntityName, '')) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
              AND (CAST(:county AS string) IS NULL OR LOWER(a.county) = LOWER(CAST(:county AS string)))
              AND (:typeId IS NULL OR a.accommodationTypeId = :typeId)
            """)
    Page<RnSummaryDto> searchRegistry(
            @Param("statuses") List<RnStatus> statuses,
            @Param("q") String q,
            @Param("county") String county,
            @Param("typeId") Long typeId,
            Pageable pageable);

    @Transactional(readOnly = true)
    @Query("""
            SELECT new com.str.backend.rn.dto.RnDetailDto(
                r.rn, r.status, r.issueDate, r.validFrom, r.validTo,
                r.createdAt, r.updatedAt, r.submissionId,
                a.accommodationId, a.county, a.city, a.settlement, a.street, a.streetNumber,
                a.name, t.name, a.maxBeds, a.maxGuests, a.category,
                l.lessorId, l.firstName, l.lastName, l.legalEntityName, l.email, l.lessorOib
            )
            FROM RnEntity r
            JOIN AccommodationEntity a ON a.accommodationId = r.accommodationId
            LEFT JOIN AccommodationTypeEntity t ON t.typeId = a.accommodationTypeId
            LEFT JOIN LessorEntity l ON l.lessorId =
                (SELECT s.lessorId FROM SubmissionEntity s WHERE s.submissionId = r.submissionId)
            WHERE r.rn = :rn
            """)
    Optional<RnDetailDto> findDetail(@Param("rn") String rn);
}
