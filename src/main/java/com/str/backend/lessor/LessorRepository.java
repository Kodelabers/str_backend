package com.str.backend.lessor;

import com.str.backend.admin.dto.PendingRegistrationSummaryDto;
import com.str.backend.domain.LessorApplicationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface LessorRepository extends JpaRepository<LessorEntity, UUID> {

    @Transactional(readOnly = true)
    Optional<LessorEntity> findByUsername(String username);

    @Transactional(readOnly = true)
    Optional<LessorEntity> findByEmail(String email);

    // --- Pending-registration megasearch (token-AND) building blocks. Shared between data + count
    // queries so the WHERE clause cannot drift between them. @Query accepts constant concatenation.

    /** Per-record searchable haystack: all fields COALESCE'd (a NULL field must not null the whole CONCAT). */
    String LESSOR_HAYSTACK =
            "LOWER(CONCAT(COALESCE(l.firstName,''),' ',COALESCE(l.lastName,''),' ',COALESCE(l.email,''),' '," +
            "COALESCE(l.taxNumber,''),' ',COALESCE(d.documentNumber,''),' ',COALESCE(c.name,'')))";

    /** One AND-ed clause per token slot; null slots are no-ops. Tokens are already lowercased by the service. */
    String LESSOR_TOKENS =
            " AND (CAST(:tok0 AS string) IS NULL OR " + LESSOR_HAYSTACK + " LIKE CONCAT('%', CAST(:tok0 AS string), '%'))" +
            " AND (CAST(:tok1 AS string) IS NULL OR " + LESSOR_HAYSTACK + " LIKE CONCAT('%', CAST(:tok1 AS string), '%'))" +
            " AND (CAST(:tok2 AS string) IS NULL OR " + LESSOR_HAYSTACK + " LIKE CONCAT('%', CAST(:tok2 AS string), '%'))" +
            " AND (CAST(:tok3 AS string) IS NULL OR " + LESSOR_HAYSTACK + " LIKE CONCAT('%', CAST(:tok3 AS string), '%'))" +
            " AND (CAST(:tok4 AS string) IS NULL OR " + LESSOR_HAYSTACK + " LIKE CONCAT('%', CAST(:tok4 AS string), '%'))" +
            " AND (CAST(:tok5 AS string) IS NULL OR " + LESSOR_HAYSTACK + " LIKE CONCAT('%', CAST(:tok5 AS string), '%'))" +
            " AND (CAST(:tok6 AS string) IS NULL OR " + LESSOR_HAYSTACK + " LIKE CONCAT('%', CAST(:tok6 AS string), '%'))" +
            " AND (CAST(:tok7 AS string) IS NULL OR " + LESSOR_HAYSTACK + " LIKE CONCAT('%', CAST(:tok7 AS string), '%'))" +
            " AND (CAST(:tok8 AS string) IS NULL OR " + LESSOR_HAYSTACK + " LIKE CONCAT('%', CAST(:tok8 AS string), '%'))" +
            " AND (CAST(:tok9 AS string) IS NULL OR " + LESSOR_HAYSTACK + " LIKE CONCAT('%', CAST(:tok9 AS string), '%'))";

    /** Per-field discrete filters (each optional, case-insensitive substring) + existing country/documentType filters. */
    String LESSOR_FILTERS =
            " AND (CAST(:name AS string) IS NULL OR LOWER(CONCAT(COALESCE(l.firstName,''),' ',COALESCE(l.lastName,''))) LIKE CONCAT('%', LOWER(CAST(:name AS string)), '%'))" +
            " AND (CAST(:email AS string) IS NULL OR LOWER(COALESCE(l.email,'')) LIKE CONCAT('%', LOWER(CAST(:email AS string)), '%'))" +
            " AND (CAST(:taxNumber AS string) IS NULL OR LOWER(COALESCE(l.taxNumber,'')) LIKE CONCAT('%', LOWER(CAST(:taxNumber AS string)), '%'))" +
            " AND (CAST(:documentNumber AS string) IS NULL OR LOWER(d.documentNumber) LIKE CONCAT('%', LOWER(CAST(:documentNumber AS string)), '%'))" +
            " AND (CAST(:country AS string) IS NULL OR LOWER(c.name) LIKE CONCAT('%', LOWER(CAST(:country AS string)), '%'))" +
            " AND (CAST(:documentType AS string) IS NULL OR d.documentType = CAST(:documentType AS string))";

    String LESSOR_FROM =
            " FROM LessorEntity l" +
            " JOIN LessorDocumentEntity d ON d.lessorId = l.lessorId" +
            " LEFT JOIN CountryEntity c ON c.id = l.countryOfResidenceId" +
            " WHERE l.applicationStatus = :status";

    @Transactional(readOnly = true)
    @Query(value = "SELECT new com.str.backend.admin.dto.PendingRegistrationSummaryDto("
            + " l.lessorId, l.firstName, l.lastName, l.email,"
            + " l.dateOfBirth, l.countryOfResidenceId, c.name, l.taxNumber,"
            + " l.applicationStatus, l.createdAt,"
            + " d.documentType, d.documentNumber)"
            + LESSOR_FROM + LESSOR_TOKENS + LESSOR_FILTERS,
            countQuery = "SELECT COUNT(l)" + LESSOR_FROM + LESSOR_TOKENS + LESSOR_FILTERS)
    Page<PendingRegistrationSummaryDto> searchRegistrations(
            @Param("status") LessorApplicationStatus status,
            @Param("tok0") String tok0, @Param("tok1") String tok1, @Param("tok2") String tok2,
            @Param("tok3") String tok3, @Param("tok4") String tok4, @Param("tok5") String tok5,
            @Param("tok6") String tok6, @Param("tok7") String tok7, @Param("tok8") String tok8,
            @Param("tok9") String tok9,
            @Param("country") String country,
            @Param("documentType") String documentType,
            @Param("name") String name,
            @Param("email") String email,
            @Param("taxNumber") String taxNumber,
            @Param("documentNumber") String documentNumber,
            Pageable pageable);

    @Transactional(readOnly = true)
    Optional<LessorEntity> findByLessorIdAndApplicationStatus(UUID lessorId, LessorApplicationStatus status);

    @Transactional(readOnly = true)
    long countByApplicationStatus(LessorApplicationStatus status);

    @Transactional(readOnly = true)
    long countByApplicationStatusAndCreatedAtGreaterThanEqual(LessorApplicationStatus status, Instant from);

    @Transactional(readOnly = true)
    long countByApplicationStatusAndCreatedAtLessThan(LessorApplicationStatus status, Instant before);
}
