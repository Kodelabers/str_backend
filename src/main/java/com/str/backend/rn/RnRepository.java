package com.str.backend.rn;

import com.str.backend.domain.RnStatus;
import com.str.backend.lessor.LessorRnSummaryDto;
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

    /** STR statistics: counts of RNs grouped by accommodation county + RN status. */
    @Transactional(readOnly = true)
    @Query("""
            SELECT a.county AS county, r.status AS status, COUNT(r) AS count
            FROM RnEntity r JOIN AccommodationEntity a ON a.accommodationId = r.accommodationId
            GROUP BY a.county, r.status
            """)
    List<CountyStatusCount> countByCountyAndStatus();

    /** STR statistics: same as above but only RNs issued within the given date range (inclusive). */
    @Transactional(readOnly = true)
    @Query("""
            SELECT a.county AS county, r.status AS status, COUNT(r) AS count
            FROM RnEntity r JOIN AccommodationEntity a ON a.accommodationId = r.accommodationId
            WHERE r.issueDate BETWEEN :from AND :to
            GROUP BY a.county, r.status
            """)
    List<CountyStatusCount> countByCountyAndStatusBetween(@Param("from") java.time.LocalDate from,
                                                          @Param("to") java.time.LocalDate to);

    interface CountyStatusCount {
        String getCounty();
        RnStatus getStatus();
        long getCount();
    }

    // --- Registry megasearch (token-AND) building blocks. Shared between data + count queries so
    // the WHERE clause cannot drift between them. @Query accepts constant string concatenation.

    /** Per-record searchable haystack: all fields COALESCE'd (a NULL field must not null the whole CONCAT). */
    String RN_HAYSTACK =
            "LOWER(CONCAT(COALESCE(r.rn,''),' ',COALESCE(a.name,''),' ',COALESCE(a.street,''),' '," +
            "COALESCE(a.streetNumber,''),' ',COALESCE(a.city,''),' ',COALESCE(l.firstName,''),' '," +
            "COALESCE(l.lastName,''),' ',COALESCE(l.legalEntityName,''),' ',COALESCE(a.county,'')))";

    /** One AND-ed clause per token slot; null slots are no-ops. Tokens are already lowercased by the service. */
    String RN_TOKENS =
            " AND (CAST(:tok0 AS string) IS NULL OR " + RN_HAYSTACK + " LIKE CONCAT('%', CAST(:tok0 AS string), '%'))" +
            " AND (CAST(:tok1 AS string) IS NULL OR " + RN_HAYSTACK + " LIKE CONCAT('%', CAST(:tok1 AS string), '%'))" +
            " AND (CAST(:tok2 AS string) IS NULL OR " + RN_HAYSTACK + " LIKE CONCAT('%', CAST(:tok2 AS string), '%'))" +
            " AND (CAST(:tok3 AS string) IS NULL OR " + RN_HAYSTACK + " LIKE CONCAT('%', CAST(:tok3 AS string), '%'))" +
            " AND (CAST(:tok4 AS string) IS NULL OR " + RN_HAYSTACK + " LIKE CONCAT('%', CAST(:tok4 AS string), '%'))" +
            " AND (CAST(:tok5 AS string) IS NULL OR " + RN_HAYSTACK + " LIKE CONCAT('%', CAST(:tok5 AS string), '%'))" +
            " AND (CAST(:tok6 AS string) IS NULL OR " + RN_HAYSTACK + " LIKE CONCAT('%', CAST(:tok6 AS string), '%'))" +
            " AND (CAST(:tok7 AS string) IS NULL OR " + RN_HAYSTACK + " LIKE CONCAT('%', CAST(:tok7 AS string), '%'))" +
            " AND (CAST(:tok8 AS string) IS NULL OR " + RN_HAYSTACK + " LIKE CONCAT('%', CAST(:tok8 AS string), '%'))" +
            " AND (CAST(:tok9 AS string) IS NULL OR " + RN_HAYSTACK + " LIKE CONCAT('%', CAST(:tok9 AS string), '%'))";

    /** Per-field discrete filters (each optional, case-insensitive substring) + existing county/type filters. */
    String RN_FILTERS =
            " AND (CAST(:rb AS string) IS NULL OR LOWER(r.rn) LIKE CONCAT('%', LOWER(CAST(:rb AS string)), '%'))" +
            " AND (CAST(:city AS string) IS NULL OR LOWER(a.city) LIKE CONCAT('%', LOWER(CAST(:city AS string)), '%'))" +
            " AND (CAST(:street AS string) IS NULL OR LOWER(a.street) LIKE CONCAT('%', LOWER(CAST(:street AS string)), '%'))" +
            " AND (CAST(:name AS string) IS NULL OR LOWER(COALESCE(a.name,'')) LIKE CONCAT('%', LOWER(CAST(:name AS string)), '%'))" +
            " AND (CAST(:lessor AS string) IS NULL OR LOWER(CONCAT(COALESCE(l.firstName,''),' ',COALESCE(l.lastName,''),' ',COALESCE(l.legalEntityName,''))) LIKE CONCAT('%', LOWER(CAST(:lessor AS string)), '%'))" +
            " AND (CAST(:county AS string) IS NULL OR LOWER(a.county) = LOWER(CAST(:county AS string)))" +
            " AND (:typeId IS NULL OR a.accommodationTypeId = :typeId)";

    String RN_FROM =
            " FROM RnEntity r" +
            " JOIN AccommodationEntity a ON a.accommodationId = r.accommodationId" +
            " LEFT JOIN AccommodationTypeEntity t ON t.typeId = a.accommodationTypeId" +
            " LEFT JOIN LessorEntity l ON l.lessorId =" +
            "     (SELECT s.lessorId FROM SubmissionEntity s WHERE s.submissionId = r.submissionId)" +
            " WHERE r.status IN :statuses";

    @Transactional(readOnly = true)
    @Query(value = "SELECT new com.str.backend.rn.dto.RnSummaryDto("
            + " r.rn, r.status, r.issueDate, r.validFrom, r.validTo,"
            + " a.accommodationId, a.county, a.city, a.street, a.streetNumber,"
            + " a.name, t.name,"
            + " l.lessorId, l.firstName, l.lastName, l.legalEntityName)"
            + RN_FROM + RN_TOKENS + RN_FILTERS,
            countQuery = "SELECT COUNT(r)" + RN_FROM + RN_TOKENS + RN_FILTERS)
    Page<RnSummaryDto> searchRegistry(
            @Param("statuses") List<RnStatus> statuses,
            @Param("tok0") String tok0, @Param("tok1") String tok1, @Param("tok2") String tok2,
            @Param("tok3") String tok3, @Param("tok4") String tok4, @Param("tok5") String tok5,
            @Param("tok6") String tok6, @Param("tok7") String tok7, @Param("tok8") String tok8,
            @Param("tok9") String tok9,
            @Param("county") String county,
            @Param("typeId") Long typeId,
            @Param("rb") String rb,
            @Param("city") String city,
            @Param("street") String street,
            @Param("name") String name,
            @Param("lessor") String lessor,
            Pageable pageable);

    @Transactional(readOnly = true)
    @Query("""
            SELECT new com.str.backend.rn.dto.RnDetailDto(
                r.rn, r.status, r.issueDate, r.validFrom, r.validTo,
                r.createdAt, r.updatedAt, r.submissionId,
                a.accommodationId, a.county, a.city, a.settlement, a.street, a.streetNumber,
                a.name, t.name, a.maxBeds, a.maxGuests, a.category,
                l.lessorId, l.firstName, l.lastName, l.legalEntityName, l.email, l.lessorOib,
                l.legalEntityOwner, c.name, l.legalEntityCity, l.legalEntityRegistrationNumber,
                l.representativeOib, l.legalRepresentativeName,
                l.representativeEmail, l.representativePhone, l.representativeAddress
            )
            FROM RnEntity r
            JOIN AccommodationEntity a ON a.accommodationId = r.accommodationId
            LEFT JOIN AccommodationTypeEntity t ON t.typeId = a.accommodationTypeId
            LEFT JOIN LessorEntity l ON l.lessorId =
                (SELECT s.lessorId FROM SubmissionEntity s WHERE s.submissionId = r.submissionId)
            LEFT JOIN CountryEntity c ON c.id = l.legalEntityCountryId
            WHERE r.rn = :rn
            """)
    Optional<RnDetailDto> findDetail(@Param("rn") String rn);

    @Transactional(readOnly = true)
    @Query("""
            SELECT new com.str.backend.lessor.LessorRnSummaryDto(
                r.rn, r.status, r.issueDate,
                a.name, a.street, a.streetNumber, a.city,
                t.name
            )
            FROM RnEntity r
            JOIN AccommodationEntity a ON a.accommodationId = r.accommodationId
            LEFT JOIN AccommodationTypeEntity t ON t.typeId = a.accommodationTypeId
            WHERE r.submissionId IN (
                SELECT s.submissionId FROM SubmissionEntity s WHERE s.lessorId = :lessorId
            )
            ORDER BY r.issueDate DESC
            """)
    List<LessorRnSummaryDto> findByLessorId(@Param("lessorId") UUID lessorId);
}
