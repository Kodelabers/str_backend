package com.str.backend.attachment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SubmissionAttachmentRepository extends JpaRepository<SubmissionAttachmentEntity, UUID> {
    List<SubmissionAttachmentEntity> findBySubmissionId(UUID submissionId);
}
