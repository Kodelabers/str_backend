package com.str.backend.request;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SubmissionLogRepository extends JpaRepository<SubmissionLogEntity, UUID> {

    List<SubmissionLogEntity> findBySubmissionIdOrderByOccurredAtAsc(UUID submissionId);
}
