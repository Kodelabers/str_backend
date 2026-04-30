package com.str.backend.request;

import com.str.backend.domain.SubmissionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubmissionRepository extends JpaRepository<SubmissionEntity, UUID> {

    Optional<SubmissionEntity> findByFilingNumber(String filingNumber);

    boolean existsByFilingNumber(String filingNumber);

    List<SubmissionEntity> findByStatus(SubmissionStatus status);

    List<SubmissionEntity> findByLessorId(UUID lessorId);
}
