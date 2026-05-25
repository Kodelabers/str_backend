package com.str.backend.lessor;

import com.str.backend.admin.dto.PendingRegistrationSummaryDto;
import com.str.backend.domain.SubmissionStatus;
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

    @Transactional(readOnly = true)
    @Query(value = """
            SELECT new com.str.backend.admin.dto.PendingRegistrationSummaryDto(
                l.lessorId, l.firstName, l.lastName, l.email,
                l.dateOfBirth, l.countryOfResidenceId, c.name, l.taxNumber,
                l.applicationStatus, l.createdAt,
                d.documentType, d.documentNumber
            )
            FROM LessorEntity l
            JOIN LessorDocumentEntity d ON d.lessorId = l.lessorId
            LEFT JOIN CountryEntity c ON c.id = l.countryOfResidenceId
            WHERE l.applicationStatus = :status
              AND (CAST(:q AS string) IS NULL OR
                   LOWER(l.firstName)      LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')) OR
                   LOWER(l.lastName)       LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')) OR
                   LOWER(d.documentNumber) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
              AND (CAST(:country AS string) IS NULL OR LOWER(c.name) LIKE LOWER(CONCAT('%', CAST(:country AS string), '%')))
              AND (CAST(:documentType AS string) IS NULL OR d.documentType = CAST(:documentType AS string))
            """,
            countQuery = """
            SELECT COUNT(l) FROM LessorEntity l
            JOIN LessorDocumentEntity d ON d.lessorId = l.lessorId
            LEFT JOIN CountryEntity c ON c.id = l.countryOfResidenceId
            WHERE l.applicationStatus = :status
              AND (CAST(:q AS string) IS NULL OR
                   LOWER(l.firstName)      LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')) OR
                   LOWER(l.lastName)       LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')) OR
                   LOWER(d.documentNumber) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
              AND (CAST(:country AS string) IS NULL OR LOWER(c.name) LIKE LOWER(CONCAT('%', CAST(:country AS string), '%')))
              AND (CAST(:documentType AS string) IS NULL OR d.documentType = CAST(:documentType AS string))
            """)
    Page<PendingRegistrationSummaryDto> searchRegistrations(
            @Param("status") SubmissionStatus status,
            @Param("q") String q,
            @Param("country") String country,
            @Param("documentType") String documentType,
            Pageable pageable);

    @Transactional(readOnly = true)
    Optional<LessorEntity> findByLessorIdAndApplicationStatus(UUID lessorId, SubmissionStatus status);

    @Transactional(readOnly = true)
    long countByApplicationStatus(SubmissionStatus status);

    @Transactional(readOnly = true)
    long countByApplicationStatusAndCreatedAtGreaterThanEqual(SubmissionStatus status, Instant from);

    @Transactional(readOnly = true)
    long countByApplicationStatusAndCreatedAtLessThan(SubmissionStatus status, Instant before);
}
